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
package de.qaware.chronix.solr.query.analysis.functions;

/**
 * The transformation interface
 *
 * @param <T> defines the type of the time series
 * @author f.lautenschlager
 */
public interface ChronixTransformation<T> {

    /**
     * Transforms a time series by changing it inital values
     *
     * @param timeSeries the time series that is transformed
     * @return the transformed time series
     */
    T transform(T timeSeries);

    /**
     * @return the type of the transformation
     */
    FunctionType getType();

}
