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
package de.qaware.chronix.solr.query.analysis

import de.qaware.chronix.converter.BinaryTimeSeries
import de.qaware.chronix.converter.KassiopeiaSimpleConverter
import de.qaware.chronix.converter.TimeSeriesConverter
import de.qaware.chronix.timeseries.MetricTimeSeries
import de.qaware.chronix.timeseries.dt.DoubleList
import de.qaware.chronix.timeseries.dt.LongList
import org.apache.lucene.document.Document
import org.apache.solr.common.SolrDocument
import org.apache.solr.common.SolrDocumentList
import spock.lang.Specification

import java.nio.ByteBuffer

/**
 * @author f.lautenschlager
 */
class AnalysisDocumentBuilderTest extends Specification {

    def "test private constructor"() {
        when:
        AnalysisDocumentBuilder.newInstance()
        then:
        noExceptionThrown()
    }


    def "test collect"() {
        given:
        def docs = new SolrDocumentList()

        2.times {
            def document = new SolrDocument()
            document.addField("host", "laptop")
            document.addField("metric", "groovy")

            docs.add(document);
        }

        def fq = ["join=metric,host"] as String[]
        def joinFunction = JoinFunctionEvaluator.joinFunction(fq)

        when:
        def collectedDocs = AnalysisDocumentBuilder.collect(docs, joinFunction)

        then:
        collectedDocs.size() == 1
        collectedDocs.get("groovy-laptop").size() == 2
    }

    List<Document> fillDocs() {
        def result = new ArrayList<SolrDocument>();

        TimeSeriesConverter<MetricTimeSeries> converter = new KassiopeiaSimpleConverter();

        10.times {
            MetricTimeSeries ts = new MetricTimeSeries.Builder("groovy")
                    .attribute("host", "laptop")
                    .attribute("someInt", 1i + it)
                    .attribute("someFloat", 1.1f + it)
                    .attribute("someDouble", [2.0d + it])
                    .attribute("_version_", "ignored")
                    .points(times(it + 1), values(it + 1))
                    .build();
            def doc = converter.to(ts)
            result.add(asSolrDoc(doc))
        }

        result
    }

    def SolrDocument asSolrDoc(BinaryTimeSeries binaryStorageDocument) {
        def doc = new SolrDocument()
        doc.addField("host", binaryStorageDocument.get("host"))
        doc.addField("data", ByteBuffer.wrap(binaryStorageDocument.getPoints()))
        doc.addField("metric", binaryStorageDocument.get("metric"))
        doc.addField("start", binaryStorageDocument.getStart())
        doc.addField("end", binaryStorageDocument.getEnd())
        doc.addField("someInt", binaryStorageDocument.get("someInt"))
        doc.addField("someFloat", binaryStorageDocument.get("someFloat"))
        doc.addField("someDouble", binaryStorageDocument.get("someDouble"))
        doc.addField("_version_", binaryStorageDocument.get("_version_"))
        doc.addField("userByteBuffer", ByteBuffer.wrap("some_user_bytes".bytes))


        doc
    }

    def LongList times(int i) {
        def times = new LongList()
        100.times {
            times.add(it * 15 + i as long)
        }
        times
    }

    def DoubleList values(int i) {
        def values = new DoubleList()

        100.times {
            values.add(it * 100 * i as double)
        }
        values
    }
}
