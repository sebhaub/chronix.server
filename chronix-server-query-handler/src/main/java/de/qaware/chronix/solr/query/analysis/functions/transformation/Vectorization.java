/*
 * Copyright (C) 2016 QAware GmbH
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package de.qaware.chronix.solr.query.analysis.functions.transformation;

import de.qaware.chronix.solr.query.analysis.functions.ChronixTransformation;
import de.qaware.chronix.solr.query.analysis.functions.FunctionType;
import de.qaware.chronix.timeseries.MetricTimeSeries;
import de.qaware.chronix.timeseries.dt.DoubleList;
import de.qaware.chronix.timeseries.dt.LongList;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.util.Arrays;

/**
 * This transformation does a vectorization of the time series by removing some points.
 *
 * @author f.lautenschlager
 */
public class Vectorization implements ChronixTransformation<MetricTimeSeries> {

    private static float DEFAULT_TOLERANCE = 0.01f;

    /**
     * Todo: Describe the algorithm, a bit.
     * <p>
     * Note: The transformation changes the values of the time series!
     * Further analyses such as aggregations uses the transformed values for the calculation.
     *
     * @param timeSeries the time series that is transformed
     * @return a vectorized time series
     */
    @Override
    public MetricTimeSeries transform(MetricTimeSeries timeSeries) {
        return transform(timeSeries, DEFAULT_TOLERANCE);
    }

    /**
     * Todo: Describe the algorithm, a bit.
     * <p>
     * Note: The transformation changes the values of the time series!
     * Further analyses such as aggregations uses the transformed values for the calculation.
     *
     * @param timeSeries the time series that is transformed
     * @return a vectorized time series
     */
    public MetricTimeSeries transform(MetricTimeSeries timeSeries, float tolerance) {
        int size = timeSeries.size();
        //do not simplify if there are insufficient data points
        if (size <= 3) {
            return timeSeries;
        }

        byte[] use_point = new byte[size];
        Arrays.fill(use_point, (byte) 1);

        long[] rawTimeStamps = timeSeries.getTimestampsAsArray();
        double[] rawValues = timeSeries.getValuesAsArray();

        compute(rawTimeStamps, rawValues, use_point, tolerance);

        LongList vectorizedTimeStamps = new LongList();
        DoubleList vectorizedValues = new DoubleList();

        for (int i = 0; i < size; i++) {
            if (use_point[i] == 1) {
                vectorizedTimeStamps.add(rawTimeStamps[i]);
                vectorizedValues.add(rawValues[i]);
            }
        }

        return new MetricTimeSeries.Builder(timeSeries.getMetric())
                .attributes(timeSeries.attributes())
                .points(vectorizedTimeStamps, vectorizedValues)
                .build();
    }

    /**
     * Calculates the distance between a point and a line.
     * The distance function is defined as:
     * <p>
     * <code>
     * (Ay-Cy)(Bx-Ax)-(Ax-Cx)(By-Ay)</p>
     * s = -----------------------------</p>
     * L^2</p>
     * </code>
     * Then the distance from C to P = |s|*L.
     */
    private double get_distance(long p_x, double p_y, long a_x, double a_y, long b_x, double b_y) {

        double l_2 = (b_x - a_x) * (b_x - a_x) + (b_y - a_y) * (b_y - a_y);
        double s = ((a_y - p_y) * (b_x - a_x) - (a_x - p_x) * (b_y - a_y)) / (l_2);

        return Math.abs(s) * Math.sqrt(l_2);
    }

    private void compute(long[] timestamps, double[] values, byte[] use_point, float tolerance) {

        int ix_a = 0;
        int ix_b = 1;
        for (int i = 2; i < timestamps.length; i++) {
            double dist = get_distance(timestamps[i], values[i], timestamps[ix_a], values[ix_a], timestamps[ix_b], values[ix_b]);

            if (dist < tolerance) {
                use_point[i - 1] = 0;
                continue;
            }
            //reached the end
            if (i + 1 == timestamps.length) {
                return;
            }
            // continue with next point
            ix_a = i;
            ix_b = i + 1;
            i++;
        }
    }

    @Override
    public FunctionType getType() {
        return FunctionType.VECTOR;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        if (obj.getClass() != getClass()) {
            return false;
        }
        return new EqualsBuilder().isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder().toHashCode();
    }
}
