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
package de.qaware.chronix.solr.response;

import de.qaware.chronix.Schema;
import de.qaware.chronix.converter.serializer.JsonKassiopeiaSimpleSerializer;
import de.qaware.chronix.converter.serializer.ProtoBufKassiopeiaSimpleSerializer;
import de.qaware.chronix.timeseries.MetricTimeSeries;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.transform.DocTransformer;
import org.apache.solr.response.transform.TransformerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Data field transformer to decompress data.
 * Overrides the data field and put the json string in it
 *
 * @author f.lautenschlager
 */
public class ChronixTransformer extends TransformerFactory {
    /**
     * The name of the field holding the raw json data
     */
    public static final String DATA_AS_JSON = "dataAsJson";

    private static final Logger LOGGER = LoggerFactory.getLogger(TransformerFactory.class);

    @Override
    public DocTransformer create(String field, SolrParams params, SolrQueryRequest req) {
        return new DataFieldSerializer(field);
    }

    /**
     * Class to transform the data field into a json representation.
     */
    private static class DataFieldSerializer extends DocTransformer {
        private final String name;

        /**
         * Constructs a solr document transformer for the date field.
         *
         * @param flName the name of the field
         */
        public DataFieldSerializer(String flName) {
            LOGGER.debug("Constructing Chronix transformer for field {}", flName);
            this.name = flName;
        }

        /**
         * @return the name of the transformer
         */
        public String getName() {
            return this.name;
        }

        /**
         * Transforms the given solr document.
         * Overrides the data field with the raw json data
         *
         * @param doc   the document
         * @param docid the doc id (not used)
         * @param score the score (not used)
         * @throws IOException when the decompressed data could be correctly encoded
         */
        @Override
        public void transform(SolrDocument doc, int docid, float score) throws IOException {
            if (doc.containsKey(Schema.DATA)) {
                LOGGER.debug("Transforming data field to json. Document {}", doc);
                //we only have to decompress the field
                MetricTimeSeries timeSeries = getRawPoints(doc);
                timeSeries.sort();
                byte[] json = new JsonKassiopeiaSimpleSerializer().toJson(timeSeries);

                doc.setField(DATA_AS_JSON, new String(json, "UTF-8"));
            }
        }

        /**
         * Gets an iterator of pairs (timestamp,value) over the time series records data.
         *
         * @param doc the solr document representing the time series record
         * @return an iterator with pairs of timestamp and value
         */
        private MetricTimeSeries getRawPoints(SolrDocument doc) {
            StoredField data = (StoredField) doc.getFieldValue(Schema.DATA);
            doc.remove(Schema.DATA);

            long tsStart = getLong(doc.getFieldValue(Schema.START));
            long tsEnd = getLong(doc.getFieldValue(Schema.END));

            MetricTimeSeries.Builder ts = new MetricTimeSeries.Builder("");
            ProtoBufKassiopeiaSimpleSerializer.from(data.binaryValue().bytes, tsStart, tsEnd, ts);
            return ts.build();
        }

        /**
         * Gets the numeric (long) representation of the fields value.
         *
         * @param field the field as object
         * @return -1 if the field value has no numeric value, otherwise the numeric value as long
         */
        private long getLong(Object field) {
            if (field instanceof LongPoint) {
                return ((LongPoint) field).numericValue().longValue();
            } else if (field instanceof StoredField) {
                return ((StoredField) field).numericValue().longValue();

            } else {
                return -1;
            }
        }
    }

}
