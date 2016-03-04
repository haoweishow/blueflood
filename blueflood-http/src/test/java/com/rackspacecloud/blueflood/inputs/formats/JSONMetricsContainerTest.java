/*
 * Copyright 2013 Rackspace
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

package com.rackspacecloud.blueflood.inputs.formats;

import com.rackspacecloud.blueflood.service.Configuration;
import com.rackspacecloud.blueflood.service.CoreConfig;
import com.rackspacecloud.blueflood.types.Metric;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.type.TypeFactory;
import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class JSONMetricsContainerTest {

    public static final String PAST_COLLECTION_TIME_REGEX = ".* is more than '259200000' milliseconds into the past\\.$";
    public static final String FUTURE_COLLECTION_TIME_REGEX = ".* is more than '600000' milliseconds into the future\\.$";
    public static final String NO_TENANT_ID_REGEX = ".* No tenantId is provided for the metric\\.";

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final long MINUTE = 60000;

    private final TypeFactory typeFactory = TypeFactory.defaultInstance();
    private final long current = System.currentTimeMillis();
    @Test
    public void testJSONMetricsContainerConstruction() throws Exception {
         // Construct the JSONMetricsContainter from JSON metric objects
        JSONMetricsContainer jsonMetricsContainer = getContainer( "ac1", generateJSONMetricsData() );

        List<Metric> metricsCollection = jsonMetricsContainer.toMetrics();

        assertTrue( jsonMetricsContainer.getValidationErrors().isEmpty() );
        assertTrue( metricsCollection.size() == 2 );
        assertEquals( "ac1.mzord.duration", metricsCollection.get( 0 ).getLocator().toString() );
        assertEquals( Long.MAX_VALUE, metricsCollection.get( 0 ).getMetricValue() );
        assertEquals( 1234566, metricsCollection.get( 0 ).getTtlInSeconds() );
        assertTrue( current - metricsCollection.get( 0 ).getCollectionTime() < MINUTE );
        assertEquals( "milliseconds", metricsCollection.get( 0 ).getUnit() );
        assertEquals( "N", metricsCollection.get( 0 ).getDataType().toString() );

        assertEquals( "ac1.mzord.status", metricsCollection.get( 1 ).getLocator().toString() );
        assertEquals( "Website is up", metricsCollection.get( 1 ).getMetricValue() );
        assertEquals( "unknown", metricsCollection.get( 1 ).getUnit() );
        assertEquals( "S", metricsCollection.get( 1 ).getDataType().toString() );
    }

    @Test
    public void testBigIntHandling() throws IOException {
        String jsonBody = "[{\"collectionTime\": " + current + ",\"ttlInSeconds\":172800,\"metricValue\":18446744073709000000,\"metricName\":\"used\",\"unit\":\"unknown\"}]";

        JSONMetricsContainer container = getContainer( "786659", jsonBody );

        List<Metric> metrics = container.toMetrics();
        assertTrue( container.getValidationErrors().isEmpty() );
    }

    @Test
    public void testDelayedMetric() throws Exception {
        long time = current - 1000 - Configuration.getInstance().getLongProperty(CoreConfig.DELAYED_METRICS_MILLIS);
        String jsonBody = "[{\"collectionTime\": " + time  + ",\"ttlInSeconds\":172800,\"metricValue\":1844,\"metricName\":\"metricName1\",\"unit\":\"unknown\"}]";

        JSONMetricsContainer container = getContainer("786659", jsonBody );

        // has a side-effect required by areDelayedMetricsPresent()
        List<Metric> metrics = container.toMetrics();

        assertTrue( container.getValidationErrors().isEmpty() );
        assertTrue( container.areDelayedMetricsPresent() );
    }

    @Test
    public void testDelayedMetricFalseForRecentMetric() throws Exception {
        String jsonBody = "[{\"collectionTime\":" + current + ",\"ttlInSeconds\":172800,\"metricValue\":1844,\"metricName\":\"metricName1\",\"unit\":\"unknown\"}]";

        JSONMetricsContainer container = getContainer( "786659", jsonBody );

        // has a side-effect required by areDelayedMetricsPresent()
        List<Metric> metrics = container.toMetrics();

        assertTrue( container.getValidationErrors().isEmpty() );
        assertFalse( container.areDelayedMetricsPresent() );
    }

    @Test
    public void testScopedJsonMetric() throws IOException {
        String jsonBody = "[{\"tenantId\": 12345, \"collectionTime\":" + current + ",\"ttlInSeconds\":172800,\"metricValue\":1844,\"metricName\":\"metricName1\",\"unit\":\"unknown\"}]";

        JSONMetricsContainer container =  getScopedContainer( "786659", jsonBody);
        assertTrue( container.getValidationErrors().isEmpty() );
    }

    @Test
    public void testScopedJsonMetricNoTenantFail() throws IOException {
        String jsonBody = "[{\"collectionTime\":" + current + ",\"ttlInSeconds\":172800,\"metricValue\":1844,\"metricName\":\"metricName1\",\"unit\":\"unknown\"}]";

        JSONMetricsContainer container =  getScopedContainer( "786659", jsonBody );
        List<String> errors = container.getValidationErrors();

        assertEquals( 1, errors.size() );
        assertTrue( Pattern.matches( NO_TENANT_ID_REGEX, errors.get( 0 ) ) );
    }

    @Test
    public void testNoCollectionTime() throws IOException {

        String jsonBody = "[{\"ttlInSeconds\":172800,\"metricValue\":1844,\"metricName\":\"metricName1\",\"unit\":\"unknown\"}]";

        JSONMetricsContainer container = getContainer( "786659", jsonBody );
        List<String> errors = container.getValidationErrors();

        assertEquals( 1, errors.size() );
        assertTrue( Pattern.matches( PAST_COLLECTION_TIME_REGEX, errors.get( 0 ) ) );
    }

    @Test
    public void testCollectionTimeFutureFail() throws IOException {

        long time = current + 1000 + Configuration.getInstance().getLongProperty( CoreConfig.AFTER_CURRENT_COLLECTIONTIME_MS );
        String jsonBody = "[{\"collectionTime\":" + time + ",\"ttlInSeconds\":172800,\"metricValue\":1844,\"metricName\":\"metricName1\",\"unit\":\"unknown\"}]";

        JSONMetricsContainer container = getContainer( "786659", jsonBody );
        List<String> errors = container.getValidationErrors();
        assertEquals( 1, errors.size() );
        assertTrue(  Pattern.matches( FUTURE_COLLECTION_TIME_REGEX, errors.get( 0 ) ) );
    }

    @Test
    public void testCollectionTimePastFail() throws IOException {

        long time = current - 1000 - Configuration.getInstance().getLongProperty( CoreConfig.BEFORE_CURRENT_COLLECTIONTIME_MS );
        String jsonBody = "[{\"collectionTime\":" + time + ",\"ttlInSeconds\":172800,\"metricValue\":1844,\"metricName\":\"metricName1\",\"unit\":\"unknown\"}]";

        JSONMetricsContainer container = getContainer( "786659", jsonBody );
        List<String> errors = container.getValidationErrors();
        assertEquals( 1, errors.size() );
        assertTrue( Pattern.matches( PAST_COLLECTION_TIME_REGEX, errors.get( 0 ) ) );
    }

    private JSONMetricsContainer getScopedContainer( String name, String jsonBody ) throws java.io.IOException {

        List<JSONMetricsContainer.JSONMetric> jsonMetrics =
                mapper.readValue(
                        jsonBody,
                        typeFactory.constructCollectionType(List.class,
                                JSONMetricsContainer.ScopedJSONMetric.class)
                );
        return new JSONMetricsContainer( name, jsonMetrics);
    }
    private JSONMetricsContainer getContainer( String name, String jsonBody ) throws java.io.IOException {

        List<JSONMetricsContainer.JSONMetric> jsonMetrics =
                mapper.readValue(
                        jsonBody,
                        typeFactory.constructCollectionType(List.class,
                                JSONMetricsContainer.JSONMetric.class)
                );
        return new JSONMetricsContainer( name, jsonMetrics);
    }

    public static List<Map<String, Object>> generateMetricsData( String metricPrefix, long collectionTime ) {

        List<Map<String, Object>> metricsList = new ArrayList<Map<String, Object>>();

        // Long metric value
        Map<String, Object> testMetric = new TreeMap<String, Object>();
        testMetric.put("metricName", metricPrefix + "mzord.duration");
        testMetric.put("ttlInSeconds", 1234566);
        testMetric.put("unit", "milliseconds");
        testMetric.put("metricValue", Long.MAX_VALUE);
        testMetric.put("collectionTime", collectionTime );
        metricsList.add(testMetric);

        // String metric value
        testMetric = new TreeMap<String, Object>();
        testMetric.put("metricName", metricPrefix + "mzord.status");
        testMetric.put("ttlInSeconds", 1234566);
        testMetric.put("unit", "unknown");
        testMetric.put("metricValue", "Website is up");
        testMetric.put("collectionTime", collectionTime );
        metricsList.add(testMetric);

        // null metric value. This shouldn't be in the final list of metrics because we ignore null valued metrics.
        testMetric = new TreeMap<String, Object>();
        testMetric.put("metricName", metricPrefix + "mzord.hipster");
        testMetric.put("ttlInSeconds", 1234566);
        testMetric.put("unit", "unknown");
        testMetric.put("metricValue", null);
        testMetric.put("collectionTime", collectionTime );
        metricsList.add(testMetric);

        return metricsList;

    }

    public static List<Map<String, Object>> generateAnnotationsData() throws Exception {
        List<Map<String, Object>> annotationsList = new ArrayList<Map<String, Object>>();

        // Long metric value
        Map<String, Object> testAnnotation = new TreeMap<String, Object>();
        testAnnotation.put("what","deployment");
        testAnnotation.put("when", Calendar.getInstance().getTimeInMillis());
        testAnnotation.put("tags","prod");
        testAnnotation.put("data","app00.restart");

        annotationsList.add(testAnnotation);
        return annotationsList;
    }

    public static String generateJSONAnnotationsData() throws Exception {

        StringWriter writer = new StringWriter();
        mapper.writeValue(writer, generateAnnotationsData());

        return writer.toString();
    }

    public static String generateJSONMetricsData( long collectionTime ) throws Exception {

        StringWriter writer = new StringWriter();
        mapper.writeValue(writer, generateMetricsData( "", collectionTime ));

        return writer.toString();
    }

    public static String generateJSONMetricsData( String metricPrefix, long collectionTime ) throws Exception {

        StringWriter writer = new StringWriter();
        mapper.writeValue(writer, generateMetricsData( metricPrefix, collectionTime ));

        return writer.toString();
    }


    public static String generateJSONMetricsData( String metricPrefix ) throws Exception {
        return generateJSONMetricsData( metricPrefix, System.currentTimeMillis() );
    }


    public static String generateJSONMetricsData() throws Exception {
        return generateJSONMetricsData( System.currentTimeMillis() );
    }

    public static String generateMultitenantJSONMetricsData() throws Exception {
        long collectionTime = System.currentTimeMillis();

        List<Map<String, Object>> dataOut = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> stringObjectMap : generateMetricsData( "", collectionTime )) {
            stringObjectMap.put("tenantId", "tenantOne");
            dataOut.add(stringObjectMap);
        }
        for (Map<String, Object> stringObjectMap : generateMetricsData( "", collectionTime )) {
            stringObjectMap.put("tenantId", "tenantTwo");
            dataOut.add(stringObjectMap);
        }

        return mapper.writeValueAsString(dataOut);
    }
}
