/*
 * Population
 * Copyright (C) 2016 Yuriy Budiyev [yuriy.budiyev@yandex.ru]
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see http://www.gnu.org/licenses/.
 */
package com.budiyev.population.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.budiyev.population.model.Result;
import com.budiyev.population.model.State;
import com.budiyev.population.model.Task;
import com.budiyev.population.model.Transition;
import com.budiyev.population.model.TransitionMode;
import com.budiyev.population.model.TransitionType;
import com.budiyev.population.util.Utils;

public class Calculator {
    /**
     * Количество десяничных знаков после разделителя в вещественных числах
     * в режиме повышенной точности
     */
    private static final int HIGHER_ACCURACY_SCALE = 384;
    private final Task mTask; // Задача
    private final double[][] mStates; // Состояния
    private final BigDecimal[][] mStatesBig; // Состояния для режима повышенной точности
    private final Lock mStatesLock = new ReentrantLock();
    private final int[] mStateIds; // Идентификаторы состояний
    private final int mStatesCount; // Количество состояний
    private final ExecutorService mExecutor; // Исполнитель (для параллельного режима)
    private final ResultCallback mResultCallback; // Обратный вызов результата
    private final ProgressCallback mProgressCallback; // Обратный вызов прогресса вычислений
    private final ThreadFactory mThreadFactory; // Фабрика потоков
    private final boolean mPrepareResultsTableData; // Подготовить результат в табличном виде
    private final boolean mPrepareResultsChartData; // Подготовить результат в графическом виде
    private volatile double mProgress; // Прогресс вычислений

    /**
     * Вычислитель
     *
     * @param task                    задача
     * @param prepareResultsTableData подготовить результат в табличном виде
     * @param prepareResultsChartData подготовить результат в графическом виде
     * @param resultCallback          обратный вызов результата
     * @param progressCallback        обратный вызов прогресса вычислений
     * @param threadFactory           Фабрика потоков для асинхронных и параллельных вычислений
     */
    private Calculator(Task task, boolean prepareResultsTableData, boolean prepareResultsChartData,
            ResultCallback resultCallback, ProgressCallback progressCallback, ThreadFactory threadFactory) {
        mTask = task;
        mPrepareResultsTableData = prepareResultsTableData;
        mPrepareResultsChartData = prepareResultsChartData;
        mResultCallback = resultCallback;
        mProgressCallback = progressCallback;
        mThreadFactory = threadFactory;
        List<State> statesList = task.getStates();
        int statesCount = statesList.size();
        mStatesCount = statesCount;
        double[][] states = new double[task.getStepsCount()][statesCount];
        int[] stateIds = new int[statesCount];
        for (int i = 0; i < statesCount; i++) {
            State state = statesList.get(i);
            states[0][i] = state.getCount();
            stateIds[i] = state.getId();
        }
        mStates = states;
        mStateIds = stateIds;
        if (task.isHigherAccuracy()) {
            int maxDelay = 0;
            for (Transition transition : task.getTransitions()) {
                maxDelay = Math.max(maxDelay, transition.getSourceDelay());
                maxDelay = Math.max(maxDelay, transition.getOperandDelay());
            }
            BigDecimal[][] statesBig = new BigDecimal[maxDelay + 2][statesCount];
            for (int i = 0; i < statesCount; i++) {
                BigDecimal value = decimalValue(statesList.get(i).getCount());
                statesBig[0][i] = value;
                statesBig[1][i] = value;
            }
            mStatesBig = statesBig;
        } else {
            mStatesBig = null;
        }
        if (task.isParallel()) {
            mExecutor = Utils.newExecutor(threadFactory);
        } else {
            mExecutor = null;
        }
    }

    private double getTotalCount(int step) {
        double totalCount = 0;
        for (int state = 0; state < mStatesCount; state++) {
            totalCount += mStates[step][state];
        }
        return totalCount;
    }

    private void copyPreviousStep(int step) {
        System.arraycopy(mStates[step - 1], 0, mStates[step], 0, mStatesCount);
    }

    private BigDecimal getTotalCountBig(int step, int currentStep) {
        BigDecimal totalCount = BigDecimal.ZERO;
        for (int state = 0; state < mStatesCount; state++) {
            totalCount = totalCount.add(mStatesBig[currentStep - step][state]);
        }
        return totalCount;
    }

    private void copyPreviousStepBig(int step, int currentStep) {
        int index = currentStep - step;
        if (index == 0) {
            for (int i = mStatesBig.length - 1; i >= 1; i--) {
                System.arraycopy(mStatesBig[i - 1], 0, mStatesBig[i], 0, mStatesBig[i].length);
            }
        }
        for (int state = 0; state < mStatesCount; state++) {
            BigDecimal value = mStatesBig[index + 1][state];
            mStatesBig[index][state] = value;
            mStates[step][state] = doubleValue(value);
        }
    }

    /**
     * Значение состояния в шаге
     *
     * @param step  номер шага
     * @param state идентификатор состояния
     * @return значение состояния
     */
    private double getState(int step, int state) {
        mStatesLock.lock();
        try {
            return mStates[step][state];
        } finally {
            mStatesLock.unlock();
        }
    }

    private BigDecimal getStateBig(int step, int currentStep, int state) {
        mStatesLock.lock();
        try {
            return mStatesBig[currentStep - step][state];
        } finally {
            mStatesLock.unlock();
        }
    }

    /**
     * Увеличение значения заданного состояния на заданном шаге на заданное значение
     *
     * @param step  номер шага
     * @param state идентификатор состояния
     * @param value значение
     */
    private void incrementState(int step, int state, double value) {
        mStatesLock.lock();
        try {
            mStates[step][state] += value;
        } finally {
            mStatesLock.unlock();
        }
    }

    private void incrementStateBig(int step, int currentStep, int state, BigDecimal value) {
        mStatesLock.lock();
        try {
            int index = currentStep - step;
            BigDecimal result = mStatesBig[index][state].add(value);
            mStatesBig[index][state] = result;
            mStates[step][state] = doubleValue(result);
        } finally {
            mStatesLock.unlock();
        }
    }

    /**
     * Уменьшение значения заданного состояния на заданном шаге на заданное значение
     *
     * @param step  номер шага
     * @param state идентификатор состояния
     * @param value значение
     */
    private void decrementState(int step, int state, double value) {
        incrementState(step, state, -value);
    }

    private void decrementStateBig(int step, int currentStep, int state, BigDecimal value) {
        mStatesLock.lock();
        try {
            int index = currentStep - step;
            BigDecimal result = mStatesBig[index][state].subtract(value);
            mStatesBig[index][state] = result;
            mStates[step][state] = doubleValue(result);
        } finally {
            mStatesLock.unlock();
        }
    }

    /**
     * Поиск позиции состояния по идентификатору
     *
     * @param id идентификатор состояния
     * @return позиция состояния
     */
    private int findState(int id) {
        if (id == State.EXTERNAL) {
            return State.EXTERNAL;
        }
        for (int i = 0; i < mStateIds.length; i++) {
            if (mStateIds[i] == id) {
                return i;
            }
        }
        return -1;
    }

    private void clearBigStates() {
        for (int i = 0; i < mStatesBig.length; i++) {
            for (int j = 0; j < mStatesBig[i].length; j++) {
                mStatesBig[i][j] = null;
            }
            mStatesBig[i] = null;
        }
    }

    /**
     * Выполнение обратного вызова результата, если он задан
     *
     * @param result результаты
     */
    private void callbackResults(Result result) {
        ResultCallback resultCallback = mResultCallback;
        if (resultCallback != null) {
            resultCallback.onResult(result);
        }
    }

    /**
     * Выполнение обратного вызова прогресса вычислений, если это необходимо и он задан
     *
     * @param step номер шага
     */
    private void callbackProgress(int step) {
        if (mProgressCallback == null) {
            return;
        }
        double progress;
        boolean needUpdate;
        int stepsCount = mTask.getStepsCount();
        if (step == 0 || stepsCount == 0) {
            progress = 0;
            needUpdate = true;
        } else if (step == stepsCount - 1 || stepsCount == 1) {
            progress = 1;
            needUpdate = true;
        } else {
            progress = (double) step / (double) (stepsCount - 1);
            needUpdate = progress - mProgress > 0.005;
        }
        if (needUpdate) {
            mProgress = progress;
            mProgressCallback.onProgressUpdate(progress);
        }
    }

    /**
     * Вычисление с обычной точностью
     */
    private Result calculateNormalAccuracy() {
        callbackProgress(0);
        List<Transition> transitions = mTask.getTransitions();
        int stepsCount = mTask.getStepsCount();
        if (mTask.isParallel()) {
            List<Future<?>> futures = new ArrayList<>(transitions.size());
            for (int step = 1; step < stepsCount; step++) {
                copyPreviousStep(step);
                double totalCount = getTotalCount(step);
                for (Transition transition : transitions) {
                    futures.add(mExecutor.submit(new TransitionActionNormalAccuracy(step, totalCount, transition)));
                }
                for (Future<?> future : futures) {
                    await(future);
                }
                futures.clear();
                callbackProgress(step);
            }
        } else {
            for (int step = 1; step < stepsCount; step++) {
                copyPreviousStep(step);
                double totalCount = getTotalCount(step);
                for (Transition transition : transitions) {
                    transitionNormalAccuracy(step, totalCount, transition);
                }
                callbackProgress(step);
            }
        }
        return new Result(mTask.getStartPoint(), mStates, mTask.getStates(), mPrepareResultsTableData,
                mPrepareResultsChartData, !mTask.isAllowNegative());
    }

    /**
     * Вычисление с повышенной точностью
     */
    private Result calculateHigherAccuracy() {
        callbackProgress(0);
        List<Transition> transitions = mTask.getTransitions();
        int stepsCount = mTask.getStepsCount();
        if (mTask.isParallel()) {
            List<Future<?>> futures = new ArrayList<>(transitions.size());
            for (int step = 1; step < stepsCount; step++) {
                copyPreviousStepBig(step, step);
                BigDecimal totalCount = getTotalCountBig(step, step);
                for (Transition transition : transitions) {
                    futures.add(mExecutor.submit(new TransitionActionHigherAccuracy(step, totalCount, transition)));
                }
                for (Future<?> future : futures) {
                    await(future);
                }
                futures.clear();
                callbackProgress(step);
            }
        } else {
            for (int step = 1; step < stepsCount; step++) {
                copyPreviousStepBig(step, step);
                BigDecimal totalCount = getTotalCountBig(step, step);
                for (Transition transition : transitions) {
                    transitionHigherAccuracy(step, totalCount, transition);
                }
                callbackProgress(step);
            }
        }
        clearBigStates();
        return new Result(mTask.getStartPoint(), mStates, mTask.getStates(), mPrepareResultsTableData,
                mPrepareResultsChartData, !mTask.isAllowNegative());
    }

    /**
     * Вычисление перехода с обычной точностью
     *
     * @param step       номер шага
     * @param totalCount общее количество автоматов на прошлом шаге
     * @param transition переход
     */
    private void transitionNormalAccuracy(int step, double totalCount, Transition transition) {
        int sourceState = findState(transition.getSourceState());
        int operandState = findState(transition.getOperandState());
        int resultState = findState(transition.getResultState());
        boolean sourceExternal = isStateExternal(sourceState);
        boolean operandExternal = isStateExternal(operandState);
        boolean resultExternal = isStateExternal(resultState);
        if (sourceExternal && operandExternal) {
            return;
        }
        int sourceIndex = delay(step - 1, transition.getSourceDelay());
        int operandIndex = delay(step - 1, transition.getOperandDelay());
        int transitionType = transition.getType();
        int transitionMode = transition.getMode();
        double sourceCoefficient = transition.getSourceCoefficient();
        double operandCoefficient = transition.getOperandCoefficient();
        double probability = transition.getProbability();
        double value = 0;
        if (transitionType == TransitionType.LINEAR) {
            if (sourceExternal) {
                double operandDensity =
                        applyCoefficientLinear(getState(operandIndex, operandState), operandCoefficient);
                value = operandDensity * probability;
                if (transitionMode == TransitionMode.RESIDUAL) {
                    value = operandDensity - value * operandCoefficient;
                }
            } else if (operandExternal) {
                value = applyCoefficientLinear(getState(sourceIndex, sourceState), sourceCoefficient) * probability;
            } else if (sourceState == operandState) {
                double density = applyCoefficientLinear(getState(sourceIndex, sourceState),
                        sourceCoefficient + operandCoefficient - 1);
                value = applyTransitionCommon(density, density, transition);
            } else {
                double sourceDensity = applyCoefficientLinear(getState(sourceIndex, sourceState), sourceCoefficient);
                double operandDensity =
                        applyCoefficientLinear(getState(operandIndex, operandState), operandCoefficient);
                value = applyTransitionCommon(Math.min(sourceDensity, operandDensity), operandDensity, transition);
            }
        } else if (transitionType == TransitionType.SOLUTE) {
            if (totalCount > 0) {
                if (sourceExternal) {
                    double operandDensity =
                            applyCoefficientPower(getState(operandIndex, operandState), operandCoefficient);
                    value = operandDensity;
                    if (operandCoefficient > 1) {
                        value /= Math.pow(totalCount, operandCoefficient - 1);
                    }
                    value = applyTransitionCommon(value, operandDensity, transition);
                } else if (operandExternal) {
                    value = applyCoefficientPower(getState(sourceIndex, sourceState), sourceCoefficient);
                    if (sourceCoefficient > 1) {
                        value /= Math.pow(totalCount, sourceCoefficient - 1);
                    }
                    value *= probability;
                } else if (sourceState == operandState) {
                    double density = applyCoefficientPower(getState(sourceIndex, sourceState),
                            sourceCoefficient + operandCoefficient);
                    value = density / Math.pow(totalCount, sourceCoefficient + operandCoefficient - 1);
                    value = applyTransitionCommon(value, density, transition);
                } else {
                    double sourceDensity = applyCoefficientPower(getState(sourceIndex, sourceState), sourceCoefficient);
                    double operandDensity =
                            applyCoefficientPower(getState(operandIndex, operandState), operandCoefficient);
                    value = sourceDensity * operandDensity /
                            Math.pow(totalCount, sourceCoefficient + operandCoefficient - 1);
                    value = applyTransitionCommon(value, operandDensity, transition);
                }
            }
        } else if (transitionType == TransitionType.BLEND) {
            if (sourceExternal) {
                double operandCount = getState(operandIndex, operandState);
                if (operandCount > 0) {
                    double operandDensity = applyCoefficientPower(operandCount, operandCoefficient);
                    value = operandDensity;
                    if (operandCoefficient > 1) {
                        value /= Math.pow(operandCount, operandCoefficient - 1);
                    }
                    value = applyTransitionCommon(value, operandDensity, transition);
                }
            } else if (operandExternal) {
                double sourceCount = getState(sourceIndex, sourceState);
                if (sourceCount > 0) {
                    value = applyCoefficientPower(sourceCount, sourceCoefficient);
                    if (sourceCoefficient > 1) {
                        value /= Math.pow(sourceCount, sourceCoefficient - 1);
                    }
                    value *= probability;
                }
            } else if (sourceState == operandState) {
                double count = getState(sourceIndex, sourceState);
                if (count > 0) {
                    double density = applyCoefficientPower(count, sourceCoefficient + operandCoefficient);
                    value = density / Math.pow(count, sourceCoefficient + operandCoefficient - 1);
                    value = applyTransitionCommon(value, density, transition);
                }
            } else {
                double sourceCount = getState(sourceIndex, sourceState);
                double operandCount = getState(operandIndex, operandState);
                double sum = sourceCount + operandCount;
                if (sum > 0) {
                    double sourceDensity = applyCoefficientPower(sourceCount, sourceCoefficient);
                    double operandDensity = applyCoefficientPower(operandCount, operandCoefficient);
                    value = sourceDensity * operandDensity / Math.pow(sum, sourceCoefficient + operandCoefficient - 1);
                    value = applyTransitionCommon(value, operandDensity, transition);
                }
            }
        }
        if (!sourceExternal && transitionMode == TransitionMode.REMOVING) {
            decrementState(step, sourceState, value * sourceCoefficient);
        }
        if (!operandExternal) {
            if (transitionMode == TransitionMode.INHIBITOR || transitionMode == TransitionMode.RESIDUAL) {
                decrementState(step, operandState, value);
            } else if (transitionMode != TransitionMode.RETAINING) {
                decrementState(step, operandState, value * operandCoefficient);
            }
        }
        if (!resultExternal) {
            incrementState(step, resultState, value * transition.getResultCoefficient());
        }
    }

    /**
     * Вычисление перехода с повышенной точностью
     *
     * @param step       номер шага
     * @param totalCount общее количество автоматов на прошлом шаге
     * @param transition переход
     */
    private void transitionHigherAccuracy(int step, BigDecimal totalCount, Transition transition) {
        int sourceState = findState(transition.getSourceState());
        int operandState = findState(transition.getOperandState());
        int resultState = findState(transition.getResultState());
        boolean sourceExternal = isStateExternal(sourceState);
        boolean operandExternal = isStateExternal(operandState);
        boolean resultExternal = isStateExternal(resultState);
        if (sourceExternal && operandExternal) {
            return;
        }
        int sourceIndex = delay(step - 1, transition.getSourceDelay());
        int operandIndex = delay(step - 1, transition.getOperandDelay());
        int transitionType = transition.getType();
        int transitionMode = transition.getMode();
        double sourceCoefficient = transition.getSourceCoefficient();
        double operandCoefficient = transition.getOperandCoefficient();
        double probability = transition.getProbability();
        BigDecimal value = BigDecimal.ZERO;
        if (transitionType == TransitionType.LINEAR) {
            if (sourceExternal) {
                BigDecimal operandDensity =
                        applyCoefficientLinear(getStateBig(operandIndex, step, operandState), operandCoefficient);
                value = multiply(operandDensity, decimalValue(probability));
                if (transitionMode == TransitionMode.RESIDUAL) {
                    value = operandDensity.subtract(multiply(value, decimalValue(operandCoefficient)));
                }
            } else if (operandExternal) {
                value = multiply(applyCoefficientLinear(getStateBig(sourceIndex, step, sourceState), sourceCoefficient),
                        decimalValue(probability));
            } else if (sourceState == operandState) {
                BigDecimal density = applyCoefficientLinear(getStateBig(sourceIndex, step, sourceState),
                        sourceCoefficient + operandCoefficient - 1);
                value = applyTransitionCommon(density, density, transition);
            } else {
                BigDecimal sourceDensity =
                        applyCoefficientLinear(getStateBig(sourceIndex, step, sourceState), sourceCoefficient);
                BigDecimal operandDensity =
                        applyCoefficientLinear(getStateBig(operandIndex, step, operandState), operandCoefficient);
                value = applyTransitionCommon(sourceDensity.min(operandDensity), operandDensity, transition);
            }
        } else if (transitionType == TransitionType.SOLUTE) {
            if (totalCount.compareTo(BigDecimal.ZERO) > 0) {
                if (sourceExternal) {
                    BigDecimal operandDensity =
                            applyCoefficientPower(getStateBig(operandIndex, step, operandState), operandCoefficient);
                    value = operandDensity;
                    if (operandCoefficient > 1) {
                        value = divide(value, power(totalCount, operandCoefficient - 1));
                    }
                    value = applyTransitionCommon(value, operandDensity, transition);
                } else if (operandExternal) {
                    value = applyCoefficientPower(getStateBig(sourceIndex, step, sourceState), sourceCoefficient);
                    if (sourceCoefficient > 1) {
                        value = divide(value, power(totalCount, sourceCoefficient - 1));
                    }
                    value = multiply(value, decimalValue(probability));
                } else if (sourceState == operandState) {
                    BigDecimal density = applyCoefficientPower(getStateBig(sourceIndex, step, sourceState),
                            sourceCoefficient + operandCoefficient);
                    value = divide(density, power(totalCount, sourceCoefficient + operandCoefficient - 1));
                    value = applyTransitionCommon(value, density, transition);
                } else {
                    BigDecimal sourceDensity =
                            applyCoefficientPower(getStateBig(sourceIndex, step, sourceState), sourceCoefficient);
                    BigDecimal operandDensity =
                            applyCoefficientPower(getStateBig(operandIndex, step, operandState), operandCoefficient);
                    value = divide(multiply(sourceDensity, operandDensity),
                            power(totalCount, sourceCoefficient + operandCoefficient - 1));
                    value = applyTransitionCommon(value, operandDensity, transition);
                }
            }
        } else if (transitionType == TransitionType.BLEND) {
            if (sourceExternal) {
                BigDecimal operandCount = getStateBig(operandIndex, step, operandState);
                if (operandCount.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal operandDensity = applyCoefficientPower(operandCount, operandCoefficient);
                    value = operandDensity;
                    if (operandCoefficient > 1) {
                        value = divide(value, power(operandCount, operandCoefficient - 1));
                    }
                    value = applyTransitionCommon(value, operandDensity, transition);
                }
            } else if (operandExternal) {
                BigDecimal sourceCount = getStateBig(sourceIndex, step, sourceState);
                if (sourceCount.compareTo(BigDecimal.ZERO) > 0) {
                    value = applyCoefficientPower(sourceCount, sourceCoefficient);
                    if (sourceCoefficient > 1) {
                        value = divide(value, power(sourceCount, sourceCoefficient - 1));
                    }
                    value = multiply(value, decimalValue(probability));
                }
            } else if (sourceState == operandState) {
                BigDecimal count = getStateBig(sourceIndex, step, sourceState);
                if (count.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal density = applyCoefficientPower(count, sourceCoefficient + operandCoefficient);
                    value = divide(density, power(count, sourceCoefficient + operandCoefficient - 1));
                    value = applyTransitionCommon(value, density, transition);
                }
            } else {
                BigDecimal sourceCount = getStateBig(sourceIndex, step, sourceState);
                BigDecimal operandCount = getStateBig(operandIndex, step, operandState);
                BigDecimal sum = sourceCount.add(operandCount);
                if (sum.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal sourceDensity = applyCoefficientPower(sourceCount, sourceCoefficient);
                    BigDecimal operandDensity = applyCoefficientPower(operandCount, operandCoefficient);
                    value = divide(multiply(sourceDensity, operandDensity),
                            power(sum, sourceCoefficient + operandCoefficient - 1));
                    value = applyTransitionCommon(value, operandDensity, transition);
                }
            }
        }
        if (!sourceExternal && transitionMode == TransitionMode.REMOVING) {
            decrementStateBig(step, step, sourceState, multiply(value, decimalValue(sourceCoefficient)));
        }
        if (!operandExternal) {
            if (transitionMode == TransitionMode.INHIBITOR || transitionMode == TransitionMode.RESIDUAL) {
                decrementStateBig(step, step, operandState, value);
            } else if (transitionMode != TransitionMode.RETAINING) {
                decrementStateBig(step, step, operandState, multiply(value, decimalValue(operandCoefficient)));
            }
        }
        if (!resultExternal) {
            incrementStateBig(step, step, resultState,
                    multiply(value, decimalValue(transition.getResultCoefficient())));
        }
    }

    /**
     * Выполнение расчётов синхронно
     *
     * @return результаты вычислений
     */
    public Result calculateSync() {
        Result result;
        if (mTask.isHigherAccuracy()) {
            result = calculateHigherAccuracy();
        } else {
            result = calculateNormalAccuracy();
        }
        callbackResults(result);
        return result;
    }

    /**
     * Выполнение расчётов асинхронно
     */
    public void calculateAsync() {
        mThreadFactory.newThread(() -> {
            Result result;
            if (mTask.isHigherAccuracy()) {
                result = calculateHigherAccuracy();
            } else {
                result = calculateNormalAccuracy();
            }
            callbackResults(result);
        }).start();
    }

    /**
     * Подождать выполнение задачи
     *
     * @param future Объект {@link Future} данной задачи
     */
    private static void await(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException | CancellationException ignored) {
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    /**
     * Применение задержки
     *
     * @param step  номер шага
     * @param delay задержка
     * @return номер шага с задержкой
     */
    private static int delay(int step, int delay) {
        if (step > delay) {
            return step - delay;
        } else {
            return 0;
        }
    }

    /**
     * Применение степенного коэффициента
     */
    private static double applyCoefficientPower(double u, double coefficient) {
        if (coefficient <= 1) {
            return u;
        }
        return Math.pow(u, coefficient) / probabilisticFactorial(coefficient);
    }

    /**
     * Применение линейного коэффициента
     */
    private static double applyCoefficientLinear(double u, double coefficient) {
        if (coefficient <= 1) {
            return u;
        }
        return u / coefficient;
    }

    /**
     * Определение, является состояние внешним или нет
     */
    private static boolean isStateExternal(int stateId) {
        return stateId == State.EXTERNAL;
    }

    /**
     * Применение степенного коэффициента
     */
    private static BigDecimal applyCoefficientPower(BigDecimal u, double coefficient) {
        if (coefficient <= 1) {
            return u;
        }
        return divide(power(u, coefficient), probabilisticFactorialBig(coefficient));
    }

    /**
     * Применение линейного коэффициента
     */
    private static BigDecimal applyCoefficientLinear(BigDecimal u, double coefficient) {
        if (coefficient <= 1) {
            return u;
        }
        return divide(u, decimalValue(coefficient));
    }

    /**
     * Применение основных операций перехода
     */
    private static BigDecimal applyTransitionCommon(BigDecimal u, BigDecimal operandDensity, Transition transition) {
        int mode = transition.getMode();
        if (mode == TransitionMode.INHIBITOR) {
            u = operandDensity.subtract(multiply(u, decimalValue(transition.getOperandCoefficient())));
        }
        u = multiply(u, decimalValue(transition.getProbability()));
        if (mode == TransitionMode.RESIDUAL) {
            u = operandDensity.subtract(multiply(u, decimalValue(transition.getOperandCoefficient())));
        }
        return u;
    }

    /**
     * Применение основных операций перехода
     */
    private static double applyTransitionCommon(double u, double operandDensity, Transition transition) {
        int mode = transition.getMode();
        if (mode == TransitionMode.INHIBITOR) {
            u = operandDensity - u * transition.getOperandCoefficient();
        }
        u *= transition.getProbability();
        if (mode == TransitionMode.RESIDUAL) {
            u = operandDensity - u * transition.getOperandCoefficient();
        }
        return u;
    }

    /**
     * Деление с точностью по-умолчанию для режима повышенной точности
     *
     * @param u делимое
     * @param v делитель
     * @return частное
     */
    private static BigDecimal divide(BigDecimal u, BigDecimal v) {
        return divide(u, v, HIGHER_ACCURACY_SCALE);
    }

    /**
     * Умножение с точностью по-умолчанию для режима повышенной точности
     *
     * @param u множитель
     * @param v множитель
     * @return произведение
     */
    private static BigDecimal multiply(BigDecimal u, BigDecimal v) {
        return multiply(u, v, HIGHER_ACCURACY_SCALE);
    }

    /**
     * Возведение в степень с точностью по-умолчанию для режима повышенной точности
     *
     * @param u        основание
     * @param exponent показатель
     * @return результат
     */
    private static BigDecimal power(BigDecimal u, double exponent) {
        return power(u, exponent, HIGHER_ACCURACY_SCALE);
    }

    private static BigDecimal exponent0(BigDecimal u, int scale) {
        BigDecimal a = BigDecimal.ONE;
        BigDecimal b = u;
        BigDecimal c = u.add(BigDecimal.ONE);
        BigDecimal d;
        for (int i = 2; ; i++) {
            b = multiply(b, u, scale);
            a = a.multiply(BigDecimal.valueOf(i));
            BigDecimal e = divide(b, a, scale);
            d = c;
            c = c.add(e);
            if (c.compareTo(d) == 0) {
                break;
            }
        }
        return c;
    }

    private static BigDecimal naturalLogarithm0(BigDecimal u, int scale) {
        int s = scale + 1;
        BigDecimal a = u;
        BigDecimal b;
        BigDecimal c = decimalValue(5).movePointLeft(s);
        for (; ; ) {
            BigDecimal d = exponent(u, s);
            b = d.subtract(a).divide(d, s, RoundingMode.DOWN);
            u = u.subtract(b);
            if (b.compareTo(c) <= 0) {
                break;
            }
        }
        return u.setScale(scale, RoundingMode.HALF_EVEN);
    }

    /**
     * Преобразование int в BigDecimal
     *
     * @param u исходное значение типа int
     * @return результат типа BigDecimal
     */
    public static BigDecimal decimalValue(int u) {
        return new BigDecimal(u);
    }

    /**
     * Преобразование long в BigDecimal
     *
     * @param u исходное значение типа long
     * @return результат типа BigDecimal
     */
    public static BigDecimal decimalValue(long u) {
        return new BigDecimal(u);
    }

    /**
     * Преобразование double в BigDecimal
     *
     * @param u исходное значение типа double
     * @return результат типа BigDecimal
     */
    public static BigDecimal decimalValue(double u) {
        return new BigDecimal(u);
    }

    /**
     * Преобразование BigDecimal в double
     *
     * @param u исходное значение типа BigDecimal
     * @return результат
     */
    public static double doubleValue(BigDecimal u) {
        return u.doubleValue();
    }

    /**
     * Вероятностный факториал.
     * Факториал вещественного числа как математическое ожидание
     * от факториалов двух соседних целых.
     *
     * @param u исходное значение
     * @return результат
     */
    public static double probabilisticFactorial(double u) {
        double result = 1;
        double r = u % 1;
        if (r > 0) {
            double v = Math.floor(u);
            for (double i = 2; i <= v; i++) {
                result *= i;
            }
            result = result * (1 - r) + result * (v + 1) * r;
        } else {
            for (double i = 2; i <= u; i++) {
                result *= i;
            }
        }
        return result;
    }

    /**
     * Вероятностный факториал.
     * Факториал вещественного числа как математическое ожидание
     * от факториалов двух соседних целых.
     *
     * @param u     исходное значение
     * @param scale количество знаков в дробной части результата
     * @return результат
     */
    public static BigDecimal probabilisticFactorialBig(double u, int scale) {
        BigDecimal result = BigDecimal.ONE;
        double r = u % 1;
        if (r > 0) {
            double v = Math.floor(u);
            for (double i = 2; i <= v; i++) {
                result = result.multiply(decimalValue(i));
            }
            result = result.multiply(BigDecimal.ONE.subtract(decimalValue(r)))
                    .add(result.multiply(decimalValue(v).add(BigDecimal.ONE)).multiply(decimalValue(r)));
        } else {
            for (double i = 2; i <= u; i++) {
                result = result.multiply(decimalValue(i));
            }
        }
        return result.setScale(scale, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal probabilisticFactorialBig(double u) {
        return probabilisticFactorialBig(u, HIGHER_ACCURACY_SCALE);
    }

    /**
     * Деление
     *
     * @param u     делимое
     * @param v     делитель
     * @param scale количество знаков в дробной части результата
     * @return частное
     */
    public static BigDecimal divide(BigDecimal u, BigDecimal v, int scale) {
        return u.divide(v, scale, RoundingMode.HALF_EVEN);
    }

    /**
     * Умножение
     *
     * @param u     множитель
     * @param v     множитель
     * @param scale количество знаков в дробной части результата
     * @return произведение
     */
    public static BigDecimal multiply(BigDecimal u, BigDecimal v, int scale) {
        return u.multiply(v).setScale(scale, RoundingMode.HALF_EVEN);
    }

    /**
     * Возведение в целочисленную степень
     *
     * @param u        основание
     * @param exponent показатель
     * @param scale    количество знаков в дробной части результата
     * @return результат
     */
    public static BigDecimal power(BigDecimal u, long exponent, int scale) {
        if (u.signum() == 0) {
            return BigDecimal.ZERO;
        }
        if (exponent < 0) {
            return BigDecimal.ONE.divide(power(u, -exponent, scale), scale, RoundingMode.HALF_EVEN);
        }
        BigDecimal p = BigDecimal.ONE;
        for (; exponent > 0; exponent >>= 1) {
            if ((exponent & 1) == 1) {
                p = p.multiply(u).setScale(scale, RoundingMode.HALF_EVEN);
            }
            u = u.multiply(u).setScale(scale, RoundingMode.HALF_EVEN);
        }
        return p;
    }

    /**
     * Возведение в вещественную степень
     *
     * @param u        основание
     * @param exponent показатель
     * @param scale    количество знаков в дробной части результата
     * @return результат
     */
    public static BigDecimal power(BigDecimal u, double exponent, int scale) {
        if (u.signum() == 0) {
            return BigDecimal.ZERO;
        }
        if (exponent % 1 == 0 && exponent <= Long.MAX_VALUE) {
            return power(u, (long) exponent, scale);
        }
        return exponent(decimalValue(exponent).multiply(naturalLogarithm(u, scale)), scale);
    }

    /**
     * Нахождение целочисленного корня
     *
     * @param u     исходное значение
     * @param index степень корня
     * @param scale количество знаков в дробной части результата
     * @return результат
     */
    public static BigDecimal root(BigDecimal u, long index, int scale) {
        if (u.signum() == 0) {
            return BigDecimal.ZERO;
        }
        int s = scale + 1;
        BigDecimal a = u;
        BigDecimal b = decimalValue(index);
        BigDecimal c = decimalValue(index - 1);
        BigDecimal d = decimalValue(5).movePointLeft(s);
        BigDecimal e;
        u = divide(u, b, scale);
        for (; ; ) {
            BigDecimal f = power(u, index - 1, s);
            BigDecimal g = multiply(u, f, s);
            BigDecimal h = a.add(c.multiply(g)).setScale(s, RoundingMode.HALF_EVEN);
            BigDecimal l = multiply(b, f, s);
            e = u;
            u = h.divide(l, s, RoundingMode.DOWN);
            if (u.subtract(e).abs().compareTo(d) <= 0) {
                break;
            }
        }
        return u;
    }

    /**
     * Возведение числа Эйлера в указанную степень
     *
     * @param u     исходное значение
     * @param scale количество знаков в дробной части результата
     * @return результат
     */
    public static BigDecimal exponent(BigDecimal u, int scale) {
        if (u.signum() == 0) {
            return BigDecimal.ONE;
        } else if (u.signum() == -1) {
            return divide(BigDecimal.ONE, exponent(u.negate(), scale));
        }
        BigDecimal a = u.setScale(0, RoundingMode.DOWN);
        if (a.signum() == 0) {
            return exponent0(u, scale);
        }
        BigDecimal b = u.subtract(a);
        BigDecimal c = BigDecimal.ONE.add(divide(b, a, scale));
        BigDecimal d = exponent0(c, scale);
        BigDecimal e = decimalValue(Long.MAX_VALUE);
        BigDecimal f = BigDecimal.ONE;
        for (; a.compareTo(e) >= 0; ) {
            f = multiply(f, power(d, Long.MAX_VALUE, scale), scale);
            a = a.subtract(e);
        }
        return multiply(f, power(d, a.longValue(), scale), scale);
    }

    /**
     * Нахождение натурального логарифма от указанного значения
     *
     * @param u     исходное значение
     * @param scale количество знаков в дробной части результата
     * @return результат
     */
    public static BigDecimal naturalLogarithm(BigDecimal u, int scale) {
        if (u.signum() <= 0) {
            throw new IllegalArgumentException("Natural logarithm is defined only on positive values.");
        }
        int a = u.toString().length() - u.scale() - 1;
        if (a < 3) {
            return naturalLogarithm0(u, scale);
        } else {
            BigDecimal b = root(u, a, scale);
            BigDecimal c = naturalLogarithm0(b, scale);
            return multiply(decimalValue(a), c, scale);
        }
    }

    /**
     * Интерполяция позиций в указанных границах
     *
     * @param start      начало
     * @param end        конец
     * @param resultSize резмер результата
     * @return результат
     */
    public static int[] interpolateIndexes(int start, int end, int resultSize) {
        int[] array = new int[resultSize];
        for (int i = 0; i < resultSize; ++i) {
            array[i] = (int) Math.round(interpolate(start, end, i / (double) resultSize));
        }
        return array;
    }

    /**
     * Линейная интерполяция
     */
    public static double interpolate(double u, double v, double f) {
        return u * (1D - f) + v * f;
    }

    /**
     * Вычисление (синхронно)
     *
     * @param task                    задача
     * @param prepareResultsTableData подготовить результат для вывода в табличном виде
     * @param prepareResultsChartData подготовить результат для вывода в графическом виде
     * @param threadFactory           Фабрика потоков для асинхронных и параллельных вычислений
     * @return результаты вычислений
     */
    public static Result calculateSync(Task task, boolean prepareResultsTableData, boolean prepareResultsChartData,
            ThreadFactory threadFactory) {
        return new Calculator(task, prepareResultsTableData, prepareResultsChartData, null, null, threadFactory)
                .calculateSync();
    }

    /**
     * Вычисление (асинхронно)
     *
     * @param task                    задача
     * @param prepareResultsTableData подготовить результат для вывода в табличном виде
     * @param prepareResultsChartData подготовить результат для вывода в графическом виде
     * @param resultCallback          обратный вызов результата
     * @param progressCallback        обратный вызов прогресса вычислений
     * @param threadFactory           Фабрика потоков для асинхронных и параллельных вычислений
     */
    public static void calculateAsync(Task task, boolean prepareResultsTableData, boolean prepareResultsChartData,
            ResultCallback resultCallback, ProgressCallback progressCallback, ThreadFactory threadFactory) {
        new Calculator(task, prepareResultsTableData, prepareResultsChartData, resultCallback, progressCallback,
                threadFactory).calculateAsync();
    }

    /**
     * Действие, представляющее собой вычисление перехода с обычной точностью
     */
    private class TransitionActionNormalAccuracy implements Runnable {
        private final int mStep;
        private final double mTotalCount;
        private final Transition mTransition;

        /**
         * @param step       номер шага
         * @param totalCount общее количество автоматов на прошлом шаге
         * @param transition переход
         */
        private TransitionActionNormalAccuracy(int step, double totalCount, Transition transition) {
            mStep = step;
            mTotalCount = totalCount;
            mTransition = transition;
        }

        @Override
        public void run() {
            transitionNormalAccuracy(mStep, mTotalCount, mTransition);
        }
    }

    /**
     * Действие, представляющее собой вычисление перехода с повышенной точностью
     */
    private class TransitionActionHigherAccuracy implements Runnable {
        private final int mStep;
        private final BigDecimal mTotalCount;
        private final Transition mTransition;

        /**
         * @param step       номер шага
         * @param totalCount общее количество автоматов на прошлом шаге
         * @param transition переход
         */
        private TransitionActionHigherAccuracy(int step, BigDecimal totalCount, Transition transition) {
            mStep = step;
            mTotalCount = totalCount;
            mTransition = transition;
        }

        @Override
        public void run() {
            transitionHigherAccuracy(mStep, mTotalCount, mTransition);
        }
    }

    public interface ResultCallback {
        /**
         * Вызывается при завершении вычислений
         *
         * @param result результаты
         */
        void onResult(Result result);
    }

    public interface ProgressCallback {
        /**
         * Вызывается при обновлении прогресса вычислений
         *
         * @param progress прогресс (0 - 1)
         */
        void onProgressUpdate(double progress);
    }
}
