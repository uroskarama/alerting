/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.alerting

import org.junit.Assert
import org.opensearch.action.admin.cluster.state.ClusterStateRequest
import org.opensearch.action.admin.indices.create.CreateIndexRequest
import org.opensearch.action.admin.indices.get.GetIndexRequest
import org.opensearch.action.admin.indices.get.GetIndexResponse
import org.opensearch.action.admin.indices.mapping.get.GetMappingsRequest
import org.opensearch.action.admin.indices.mapping.put.PutMappingRequest
import org.opensearch.action.admin.indices.refresh.RefreshRequest
import org.opensearch.action.search.SearchRequest
import org.opensearch.action.support.WriteRequest
import org.opensearch.alerting.action.SearchMonitorAction
import org.opensearch.alerting.action.SearchMonitorRequest
import org.opensearch.alerting.alerts.AlertIndices
import org.opensearch.alerting.core.ScheduledJobIndices
import org.opensearch.alerting.transport.AlertingSingleNodeTestCase
import org.opensearch.common.settings.Settings
import org.opensearch.common.xcontent.XContentType
import org.opensearch.commons.alerting.action.AcknowledgeAlertRequest
import org.opensearch.commons.alerting.action.AlertingActions
import org.opensearch.commons.alerting.action.DeleteMonitorRequest
import org.opensearch.commons.alerting.action.GetAlertsRequest
import org.opensearch.commons.alerting.model.Alert
import org.opensearch.commons.alerting.model.DataSources
import org.opensearch.commons.alerting.model.DocLevelMonitorInput
import org.opensearch.commons.alerting.model.DocLevelQuery
import org.opensearch.commons.alerting.model.ScheduledJob
import org.opensearch.commons.alerting.model.ScheduledJob.Companion.DOC_LEVEL_QUERIES_INDEX
import org.opensearch.commons.alerting.model.ScheduledJob.Companion.SCHEDULED_JOBS_INDEX
import org.opensearch.commons.alerting.model.Table
import org.opensearch.index.query.MatchQueryBuilder
import org.opensearch.index.query.QueryBuilders
import org.opensearch.search.builder.SearchSourceBuilder
import org.opensearch.test.OpenSearchTestCase
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit.MILLIS
import java.util.Map
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

class MonitorDataSourcesIT : AlertingSingleNodeTestCase() {

    fun `test execute monitor with dryrun`() {
        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(index), listOf(docQuery))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger),
        )
        val monitorResponse = createMonitor(monitor)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(MILLIS))
        val testDoc = """{
            "message" : "This is an error from IAD region",
            "test_strict_date_time" : "$testTime",
            "test_field" : "us-west-2"
        }"""
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        indexDoc(index, "1", testDoc)
        val id = monitorResponse.id
        val executeMonitorResponse = executeMonitor(monitor, id, true)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        searchAlerts(id)
        val table = Table("asc", "id", null, 1, 0, "")
        var getAlertsResponse = client()
            .execute(AlertingActions.GET_ALERTS_ACTION_TYPE, GetAlertsRequest(table, "ALL", "ALL", null, null))
            .get()
        Assert.assertTrue(getAlertsResponse != null)
        Assert.assertTrue(getAlertsResponse.alerts.size == 0)
        getAlertsResponse = client()
            .execute(AlertingActions.GET_ALERTS_ACTION_TYPE, GetAlertsRequest(table, "ALL", "ALL", id, null))
            .get()
        Assert.assertTrue(getAlertsResponse != null)
        Assert.assertTrue(getAlertsResponse.alerts.size == 0)
        try {
            client()
                .execute(AlertingActions.GET_ALERTS_ACTION_TYPE, GetAlertsRequest(table, "ALL", "ALL", null, "wrong_alert_index"))
                .get()
            fail()
        } catch (e: Exception) {
            Assert.assertTrue(e.message!!.contains("IndexNotFoundException"))
        }
    }

    fun `test execute monitor with custom alerts index`() {
        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(index), listOf(docQuery))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        val customAlertsIndex = "custom_alerts_index"
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger),
            dataSources = DataSources(alertsIndex = customAlertsIndex)
        )
        val monitorResponse = createMonitor(monitor)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(MILLIS))
        val testDoc = """{
            "message" : "This is an error from IAD region",
            "test_strict_date_time" : "$testTime",
            "test_field" : "us-west-2"
        }"""
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        indexDoc(index, "1", testDoc)
        val id = monitorResponse.id
        val executeMonitorResponse = executeMonitor(monitor, id, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        val alerts = searchAlerts(id, customAlertsIndex)
        assertEquals("Alert saved for test monitor", 1, alerts.size)
        val table = Table("asc", "id", null, 1, 0, "")
        var getAlertsResponse = client()
            .execute(AlertingActions.GET_ALERTS_ACTION_TYPE, GetAlertsRequest(table, "ALL", "ALL", null, customAlertsIndex))
            .get()
        Assert.assertTrue(getAlertsResponse != null)
        Assert.assertTrue(getAlertsResponse.alerts.size == 1)
        getAlertsResponse = client()
            .execute(AlertingActions.GET_ALERTS_ACTION_TYPE, GetAlertsRequest(table, "ALL", "ALL", id, null))
            .get()
        Assert.assertTrue(getAlertsResponse != null)
        Assert.assertTrue(getAlertsResponse.alerts.size == 1)
        val alertId = getAlertsResponse.alerts.get(0).id
        val acknowledgeAlertResponse = client().execute(
            AlertingActions.ACKNOWLEDGE_ALERTS_ACTION_TYPE,
            AcknowledgeAlertRequest(id, listOf(alertId), WriteRequest.RefreshPolicy.IMMEDIATE)
        ).get()
        Assert.assertEquals(acknowledgeAlertResponse.acknowledged.size, 1)
    }

    fun `test execute monitor with custom query index`() {
        val docQuery1 = DocLevelQuery(query = "source.ip.v6.v1:12345", name = "3")
        val docQuery2 = DocLevelQuery(query = "source.ip.v6.v2:16645", name = "4")
        val docQuery3 = DocLevelQuery(query = "source.ip.v4.v0:120", name = "5")
        val docQuery4 = DocLevelQuery(query = "alias.some.fff:\"us-west-2\"", name = "6")
        val docQuery5 = DocLevelQuery(query = "message:\"This is an error from IAD region\"", name = "7")
        val docQuery6 = DocLevelQuery(query = "f1.type.f4:\"hello\"", name = "8")
        val docQuery7 = DocLevelQuery(query = "f1.type.f2.f3:\"world\"", name = "9")
        val docQuery8 = DocLevelQuery(query = "type:\"some type\"", name = "10")

        val docLevelInput = DocLevelMonitorInput(
            "description", listOf(index), listOf(docQuery1, docQuery2, docQuery3, docQuery4, docQuery5, docQuery6, docQuery7, docQuery8)
        )
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        val customFindingsIndex = "custom_findings_index"
        val customFindingsIndexPattern = "custom_findings_index-1"
        val customQueryIndex = "custom_alerts_index"
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger),
            dataSources = DataSources(
                queryIndex = customQueryIndex,
                findingsIndex = customFindingsIndex,
                findingsIndexPattern = customFindingsIndexPattern
            )
        )
        val monitorResponse = createMonitor(monitor)
        // Trying to test here few different "nesting" situations and "wierd" characters
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(MILLIS))
        val testDoc = """{
            "message" : "This is an error from IAD region",
            "source.ip.v6.v1" : 12345,
            "source.ip.v6.v2" : 16645,
            "source.ip.v4.v0" : 120,
            "test_bad_char" : "\u0000", 
            "test_strict_date_time" : "$testTime",
            "test_field.some_other_field" : "us-west-2",
            "f1.type.f2.f3" : "world",
            "f1.type.f4" : "hello",
            "type" : "some type"
        }"""
        indexDoc(index, "1", testDoc)
        client().admin().indices().putMapping(
            PutMappingRequest(index).source("alias.some.fff", "type=alias,path=test_field.some_other_field")
        )
        val mappings = "{\"properties\":{\"type\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"," +
            "\"ignore_above\":256}}},\"query\":{\"type\":\"text\"}}}"
        val mappingsResp = client().admin().indices().putMapping(
            PutMappingRequest(index).source(mappings, XContentType.JSON)
        ).get()
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        val id = monitorResponse.id
        val executeMonitorResponse = executeMonitor(monitor, id, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        searchAlerts(id)
        val table = Table("asc", "id", null, 1, 0, "")
        var getAlertsResponse = client()
            .execute(AlertingActions.GET_ALERTS_ACTION_TYPE, GetAlertsRequest(table, "ALL", "ALL", null, null))
            .get()
        Assert.assertTrue(getAlertsResponse != null)
        Assert.assertTrue(getAlertsResponse.alerts.size == 1)
        val findings = searchFindings(id, customFindingsIndex)
        assertEquals("Findings saved for test monitor", 1, findings.size)
        assertTrue("Findings saved for test monitor", findings[0].relatedDocIds.contains("1"))
        assertEquals("Didn't match all 8 queries", 8, findings[0].docLevelQueries.size)
    }

    fun `test execute monitor with custom query index old`() {
        val docQuery1 = DocLevelQuery(query = "source.ip.v6.v1:12345", name = "3")
        val docQuery2 = DocLevelQuery(query = "source.ip.v6.v2:16645", name = "4")
        val docQuery3 = DocLevelQuery(query = "source.ip.v4.v0:120", name = "5")
        val docQuery4 = DocLevelQuery(query = "alias.some.fff:\"us-west-2\"", name = "6")
        val docQuery5 = DocLevelQuery(query = "message:\"This is an error from IAD region\"", name = "7")
        val docQuery6 = DocLevelQuery(query = "type.subtype:\"some subtype\"", name = "8")
        val docQuery7 = DocLevelQuery(query = "supertype.type:\"some type\"", name = "9")
        val docLevelInput = DocLevelMonitorInput(
            "description", listOf(index), listOf(docQuery1, docQuery2, docQuery3, docQuery4, docQuery5, docQuery6, docQuery7)
        )
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        val customFindingsIndex = "custom_findings_index"
        val customFindingsIndexPattern = "custom_findings_index-1"
        val customQueryIndex = "custom_alerts_index"
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger),
            dataSources = DataSources(
                queryIndex = customQueryIndex,
                findingsIndex = customFindingsIndex,
                findingsIndexPattern = customFindingsIndexPattern
            )
        )
        val monitorResponse = createMonitor(monitor)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(MILLIS))
        // Trying to test here few different "nesting" situations and "wierd" characters
        val testDoc = """{
            "message" : "This is an error from IAD region",
            "source.ip.v6.v1" : 12345,
            "source.ip.v6.v2" : 16645,
            "source.ip.v4.v0" : 120,
            "test_bad_char" : "\u0000", 
            "test_strict_date_time" : "$testTime",
            "test_field.some_other_field" : "us-west-2",
            "type.subtype" : "some subtype",
            "supertype.type" : "some type"
        }"""
        indexDoc(index, "1", testDoc)
        client().admin().indices().putMapping(
            PutMappingRequest(index).source("alias.some.fff", "type=alias,path=test_field.some_other_field")
        )
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        val id = monitorResponse.id
        val executeMonitorResponse = executeMonitor(monitor, id, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        searchAlerts(id)
        val table = Table("asc", "id", null, 1, 0, "")
        var getAlertsResponse = client()
            .execute(AlertingActions.GET_ALERTS_ACTION_TYPE, GetAlertsRequest(table, "ALL", "ALL", null, null))
            .get()
        Assert.assertTrue(getAlertsResponse != null)
        Assert.assertTrue(getAlertsResponse.alerts.size == 1)
        val findings = searchFindings(id, customFindingsIndex)
        assertEquals("Findings saved for test monitor", 1, findings.size)
        assertTrue("Findings saved for test monitor", findings[0].relatedDocIds.contains("1"))
        assertEquals("Didn't match all 7 queries", 7, findings[0].docLevelQueries.size)
    }

    fun `test execute monitor with custom query index and nested mappings`() {
        val docQuery1 = DocLevelQuery(query = "message:\"msg 1 2 3 4\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(index), listOf(docQuery1))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        val customFindingsIndex = "custom_findings_index"
        val customFindingsIndexPattern = "custom_findings_index-1"
        val customQueryIndex = "custom_alerts_index"
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger),
            dataSources = DataSources(
                queryIndex = customQueryIndex,
                findingsIndex = customFindingsIndex,
                findingsIndexPattern = customFindingsIndexPattern
            )
        )
        val monitorResponse = createMonitor(monitor)

        // We are verifying here that index with nested mappings and nested aliases
        // won't break query matching

        // Create index mappings
        val m: MutableMap<String, Any> = HashMap()
        val m1: MutableMap<String, Any> = HashMap()
        m1["title"] = Map.of("type", "text")
        m1["category"] = Map.of("type", "keyword")
        m["rule"] = Map.of("type", "nested", "properties", m1)
        val properties = Map.of<String, Any>("properties", m)

        client().admin().indices().putMapping(
            PutMappingRequest(
                index
            ).source(properties)
        ).get()

        // Put alias for nested fields
        val mm: MutableMap<String, Any> = HashMap()
        val mm1: MutableMap<String, Any> = HashMap()
        mm1["title_alias"] = Map.of("type", "alias", "path", "rule.title")
        mm["rule"] = Map.of("type", "nested", "properties", mm1)
        val properties1 = Map.of<String, Any>("properties", mm)
        client().admin().indices().putMapping(
            PutMappingRequest(
                index
            ).source(properties1)
        ).get()

        val testDoc = """{
            "rule": {"title": "some_title"},
            "message": "msg 1 2 3 4"
        }"""
        indexDoc(index, "2", testDoc)

        client().admin().indices().putMapping(
            PutMappingRequest(index).source("alias.some.fff", "type=alias,path=test_field.some_other_field")
        )
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        val id = monitorResponse.id
        val executeMonitorResponse = executeMonitor(monitor, id, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        searchAlerts(id)
        val table = Table("asc", "id", null, 1, 0, "")
        var getAlertsResponse = client()
            .execute(AlertingActions.GET_ALERTS_ACTION_TYPE, GetAlertsRequest(table, "ALL", "ALL", null, null))
            .get()
        Assert.assertTrue(getAlertsResponse != null)
        Assert.assertTrue(getAlertsResponse.alerts.size == 1)
        val findings = searchFindings(id, customFindingsIndex)
        assertEquals("Findings saved for test monitor", 1, findings.size)
        assertTrue("Findings saved for test monitor", findings[0].relatedDocIds.contains("2"))
        assertEquals("Didn't match all 4 queries", 1, findings[0].docLevelQueries.size)
    }

    fun `test execute monitor with custom query index and custom field mappings`() {
        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(index), listOf(docQuery))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        val customQueryIndex = "custom_alerts_index"
        val analyzer = "whitespace"
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger),
            dataSources = DataSources(
                queryIndex = customQueryIndex,
                queryIndexMappingsByType = mapOf(Pair("text", mapOf(Pair("analyzer", analyzer)))),
            )
        )
        val monitorResponse = createMonitor(monitor)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(MILLIS))
        val testDoc = """{
            "message" : "This is an error from IAD region",
            "test_strict_date_time" : "$testTime",
            "test_field" : "us-west-2"
        }"""
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        indexDoc(index, "1", testDoc)
        val id = monitorResponse.id
        val executeMonitorResponse = executeMonitor(monitor, id, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        searchAlerts(id)
        val mapping = client().admin().indices().getMappings(GetMappingsRequest().indices(customQueryIndex)).get()
        Assert.assertTrue(mapping.toString().contains("\"analyzer\":\"$analyzer\""))
    }

    fun `test delete monitor deletes all queries and metadata too`() {
        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(index), listOf(docQuery))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        val customQueryIndex = "custom_query_index"
        val analyzer = "whitespace"
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger),
            dataSources = DataSources(
                queryIndex = customQueryIndex,
                queryIndexMappingsByType = mapOf(Pair("text", mapOf(Pair("analyzer", analyzer)))),
            )
        )
        val monitorResponse = createMonitor(monitor)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(MILLIS))
        val testDoc = """{
            "message" : "This is an error from IAD region",
            "test_strict_date_time" : "$testTime",
            "test_field" : "us-west-2"
        }"""
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        indexDoc(index, "1", testDoc)
        val monitorId = monitorResponse.id
        val executeMonitorResponse = executeMonitor(monitor, monitorId, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        searchAlerts(monitorId)
        val clusterStateResponse = client().admin().cluster().state(ClusterStateRequest().indices(customQueryIndex).metadata(true)).get()
        val mapping = client().admin().indices().getMappings(GetMappingsRequest().indices(customQueryIndex)).get()
        Assert.assertTrue(mapping.toString().contains("\"analyzer\":\"$analyzer\"") == true)
        // Verify queries exist
        var searchResponse = client().search(
            SearchRequest(customQueryIndex).source(SearchSourceBuilder().query(QueryBuilders.matchAllQuery()))
        ).get()
        assertNotEquals(0, searchResponse.hits.hits.size)

        deleteMonitor(monitorId)
        assertIndexNotExists(customQueryIndex + "*")
        assertAliasNotExists(customQueryIndex)
    }

    fun `test execute monitor with custom findings index and pattern`() {
        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(index), listOf(docQuery))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        val customFindingsIndex = "custom_findings_index"
        val customFindingsIndexPattern = "<custom_findings_index-{now/d}-1>"
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger),
            dataSources = DataSources(findingsIndex = customFindingsIndex, findingsIndexPattern = customFindingsIndexPattern)
        )
        val monitorResponse = createMonitor(monitor)
        client().admin().indices().refresh(RefreshRequest("*"))
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(MILLIS))
        val testDoc = """{
            "message" : "This is an error from IAD region",
            "test_strict_date_time" : "$testTime",
            "test_field" : "us-west-2"
        }"""
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        indexDoc(index, "1", testDoc)
        val id = monitorResponse.id
        var executeMonitorResponse = executeMonitor(monitor, id, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)

        var findings = searchFindings(id, "custom_findings_index*", true)
        assertEquals("Findings saved for test monitor", 1, findings.size)
        assertTrue("Findings saved for test monitor", findings[0].relatedDocIds.contains("1"))

        indexDoc(index, "2", testDoc)
        executeMonitorResponse = executeMonitor(monitor, id, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        searchAlerts(id)
        findings = searchFindings(id, "custom_findings_index*", true)
        assertEquals("Findings saved for test monitor", 2, findings.size)
        assertTrue("Findings saved for test monitor", findings[1].relatedDocIds.contains("2"))

        val indices = getAllIndicesFromPattern("custom_findings_index*")
        Assert.assertTrue(indices.isNotEmpty())
    }

    fun `test execute pre-existing monitor and update`() {
        val request = CreateIndexRequest(SCHEDULED_JOBS_INDEX).mapping(ScheduledJobIndices.scheduledJobMappings())
            .settings(Settings.builder().put("index.hidden", true).build())
        client().admin().indices().create(request)
        val monitorStringWithoutName = """
        {
        "monitor": {
        "type": "monitor",
        "schema_version": 0,
        "name": "UayEuXpZtb",
        "monitor_type": "doc_level_monitor",
        "user": {
        "name": "",
        "backend_roles": [],
        "roles": [],
        "custom_attribute_names": [],
        "user_requested_tenant": null
        },
        "enabled": true,
        "enabled_time": 1662753436791,
        "schedule": {
        "period": {
        "interval": 5,
        "unit": "MINUTES"
        }
        },
        "inputs": [{
        "doc_level_input": {
        "description": "description",
        "indices": [
        "$index"
        ],
        "queries": [{
        "id": "63efdcce-b5a1-49f4-a25f-6b5f9496a755",
        "name": "3",
        "query": "test_field:\"us-west-2\"",
        "tags": []
        }]
        }
        }],
        "triggers": [{
        "document_level_trigger": {
        "id": "OGnTI4MBv6qt0ATc9Phk",
        "name": "mrbHRMevYI",
        "severity": "1",
        "condition": {
        "script": {
        "source": "return true",
        "lang": "painless"
        }
        },
        "actions": []
        }
        }],
        "last_update_time": 1662753436791
        }
        }
        """.trimIndent()
        val monitorId = "abc"
        indexDoc(SCHEDULED_JOBS_INDEX, monitorId, monitorStringWithoutName)
        val getMonitorResponse = getMonitorResponse(monitorId)
        Assert.assertNotNull(getMonitorResponse)
        Assert.assertNotNull(getMonitorResponse.monitor)
        val monitor = getMonitorResponse.monitor

        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(MILLIS))
        val testDoc = """{
            "message" : "This is an error from IAD region",
            "test_strict_date_time" : "$testTime",
            "test_field" : "us-west-2"
        }"""
        indexDoc(index, "1", testDoc)
        var executeMonitorResponse = executeMonitor(monitor!!, monitorId, false)
        Assert.assertNotNull(executeMonitorResponse)
        if (executeMonitorResponse != null) {
            Assert.assertNotNull(executeMonitorResponse.monitorRunResult.monitorName)
        }
        val alerts = searchAlerts(monitorId)
        assertEquals(1, alerts.size)

        val customAlertsIndex = "custom_alerts_index"
        val customQueryIndex = "custom_query_index"
        val customFindingsIndex = "custom_findings_index"
        val updateMonitorResponse = updateMonitor(
            monitor.copy(
                id = monitorId,
                owner = "security_analytics_plugin",
                dataSources = DataSources(
                    alertsIndex = customAlertsIndex,
                    queryIndex = customQueryIndex,
                    findingsIndex = customFindingsIndex
                )
            ),
            monitorId
        )
        Assert.assertNotNull(updateMonitorResponse)
        Assert.assertEquals(updateMonitorResponse!!.monitor.owner, "security_analytics_plugin")
        indexDoc(index, "2", testDoc)
        if (updateMonitorResponse != null) {
            executeMonitorResponse = executeMonitor(updateMonitorResponse.monitor, monitorId, false)
        }
        val findings = searchFindings(monitorId, customFindingsIndex)
        assertEquals("Findings saved for test monitor", 1, findings.size)
        assertTrue("Findings saved for test monitor", findings[0].relatedDocIds.contains("2"))
        val customAlertsIndexAlerts = searchAlerts(monitorId, customAlertsIndex)
        assertEquals("Alert saved for test monitor", 1, customAlertsIndexAlerts.size)
        val table = Table("asc", "id", null, 1, 0, "")
        var getAlertsResponse = client()
            .execute(AlertingActions.GET_ALERTS_ACTION_TYPE, GetAlertsRequest(table, "ALL", "ALL", null, customAlertsIndex))
            .get()
        Assert.assertTrue(getAlertsResponse != null)
        Assert.assertTrue(getAlertsResponse.alerts.size == 1)
        getAlertsResponse = client()
            .execute(AlertingActions.GET_ALERTS_ACTION_TYPE, GetAlertsRequest(table, "ALL", "ALL", monitorId, null))
            .get()
        Assert.assertTrue(getAlertsResponse != null)
        Assert.assertTrue(getAlertsResponse.alerts.size == 1)
        val searchRequest = SearchRequest(SCHEDULED_JOBS_INDEX)
        var searchMonitorResponse =
            client().execute(SearchMonitorAction.INSTANCE, SearchMonitorRequest(searchRequest))
                .get()
        Assert.assertEquals(searchMonitorResponse.hits.hits.size, 0)
        searchRequest.source().query(MatchQueryBuilder("monitor.owner", "security_analytics_plugin"))
        searchMonitorResponse =
            client().execute(SearchMonitorAction.INSTANCE, SearchMonitorRequest(searchRequest))
                .get()
        Assert.assertEquals(searchMonitorResponse.hits.hits.size, 1)
    }

    fun `test execute GetFindingsAction with monitorId param`() {
        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(index), listOf(docQuery))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        val customFindingsIndex = "custom_findings_index"
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger),
            dataSources = DataSources(findingsIndex = customFindingsIndex)
        )
        val monitorResponse = createMonitor(monitor)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(MILLIS))
        val testDoc = """{
            "message" : "This is an error from IAD region",
            "test_strict_date_time" : "$testTime",
            "test_field" : "us-west-2"
        }"""
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        indexDoc(index, "1", testDoc)
        val monitorId = monitorResponse.id
        val executeMonitorResponse = executeMonitor(monitor, monitorId, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        searchAlerts(monitorId)
        val findings = searchFindings(monitorId, customFindingsIndex)
        assertEquals("Findings saved for test monitor", 1, findings.size)
        assertTrue("Findings saved for test monitor", findings[0].relatedDocIds.contains("1"))
        // fetch findings - pass monitorId as reference to finding_index
        val findingsFromAPI = getFindings(findings.get(0).id, monitorId, null)
        assertEquals(
            "Findings mismatch between manually searched and fetched via GetFindingsAction",
            findings.get(0).id,
            findingsFromAPI.get(0).id
        )
    }

    fun `test execute GetFindingsAction with unknown monitorId`() {
        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(index), listOf(docQuery))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        val customFindingsIndex = "custom_findings_index"
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger),
            dataSources = DataSources(findingsIndex = customFindingsIndex)
        )
        val monitorResponse = createMonitor(monitor)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(MILLIS))
        val testDoc = """{
            "message" : "This is an error from IAD region",
            "test_strict_date_time" : "$testTime",
            "test_field" : "us-west-2"
        }"""
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        indexDoc(index, "1", testDoc)
        val monitorId = monitorResponse.id
        val executeMonitorResponse = executeMonitor(monitor, monitorId, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        searchAlerts(monitorId)
        val findings = searchFindings(monitorId, customFindingsIndex)
        assertEquals("Findings saved for test monitor", 1, findings.size)
        assertTrue("Findings saved for test monitor", findings[0].relatedDocIds.contains("1"))
        // fetch findings - don't send monitorId or findingIndexName. It should fall back to hardcoded finding index name
        try {
            getFindings(findings.get(0).id, "unknown_monitor_id_123456789", null)
        } catch (e: Exception) {
            e.message?.let {
                assertTrue(
                    "Exception not returning GetMonitor Action error ",
                    it.contains("Monitor not found")
                )
            }
        }
    }

    fun `test execute monitor with owner field`() {
        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(index), listOf(docQuery))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        val customAlertsIndex = "custom_alerts_index"
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger),
            dataSources = DataSources(alertsIndex = customAlertsIndex),
            owner = "owner"
        )
        val monitorResponse = createMonitor(monitor)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(MILLIS))
        val testDoc = """{
            "message" : "This is an error from IAD region",
            "test_strict_date_time" : "$testTime",
            "test_field" : "us-west-2"
        }"""
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        Assert.assertEquals(monitor.owner, "owner")
        indexDoc(index, "1", testDoc)
        val id = monitorResponse.id
        val executeMonitorResponse = executeMonitor(monitor, id, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        val alerts = searchAlerts(id, customAlertsIndex)
        assertEquals("Alert saved for test monitor", 1, alerts.size)
        val table = Table("asc", "id", null, 1, 0, "")
        var getAlertsResponse = client()
            .execute(AlertingActions.GET_ALERTS_ACTION_TYPE, GetAlertsRequest(table, "ALL", "ALL", null, customAlertsIndex))
            .get()
        Assert.assertTrue(getAlertsResponse != null)
        Assert.assertTrue(getAlertsResponse.alerts.size == 1)
        getAlertsResponse = client()
            .execute(AlertingActions.GET_ALERTS_ACTION_TYPE, GetAlertsRequest(table, "ALL", "ALL", id, null))
            .get()
        Assert.assertTrue(getAlertsResponse != null)
        Assert.assertTrue(getAlertsResponse.alerts.size == 1)
    }

    fun `test execute GetFindingsAction with unknown findingIndex param`() {
        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(index), listOf(docQuery))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        val customFindingsIndex = "custom_findings_index"
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger),
            dataSources = DataSources(findingsIndex = customFindingsIndex)
        )
        val monitorResponse = createMonitor(monitor)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(MILLIS))
        val testDoc = """{
            "message" : "This is an error from IAD region",
            "test_strict_date_time" : "$testTime",
            "test_field" : "us-west-2"
        }"""
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        indexDoc(index, "1", testDoc)
        val monitorId = monitorResponse.id
        val executeMonitorResponse = executeMonitor(monitor, monitorId, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        searchAlerts(monitorId)
        val findings = searchFindings(monitorId, customFindingsIndex)
        assertEquals("Findings saved for test monitor", 1, findings.size)
        assertTrue("Findings saved for test monitor", findings[0].relatedDocIds.contains("1"))
        // fetch findings - don't send monitorId or findingIndexName. It should fall back to hardcoded finding index name
        try {
            getFindings(findings.get(0).id, null, "unknown_finding_index_123456789")
        } catch (e: Exception) {
            e.message?.let {
                assertTrue(
                    "Exception not returning GetMonitor Action error ",
                    it.contains("no such index")
                )
            }
        }
    }

    fun `test search custom alerts history index`() {
        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(index), listOf(docQuery))
        val trigger1 = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        val trigger2 = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        val customAlertsIndex = "custom_alerts_index"
        val customAlertsHistoryIndex = "custom_alerts_history_index"
        val customAlertsHistoryIndexPattern = "<custom_alerts_history_index-{now/d}-1>"
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger1, trigger2),
            dataSources = DataSources(
                alertsIndex = customAlertsIndex,
                alertsHistoryIndex = customAlertsHistoryIndex,
                alertsHistoryIndexPattern = customAlertsHistoryIndexPattern
            )
        )
        val monitorResponse = createMonitor(monitor)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(MILLIS))
        val testDoc = """{
            "message" : "This is an error from IAD region",
            "test_strict_date_time" : "$testTime",
            "test_field" : "us-west-2"
        }"""
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        indexDoc(index, "1", testDoc)
        val monitorId = monitorResponse.id
        val executeMonitorResponse = executeMonitor(monitor, monitorId, false)
        var alertsBefore = searchAlerts(monitorId, customAlertsIndex)
        Assert.assertEquals(2, alertsBefore.size)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 2)
        // Remove 1 trigger from monitor to force moveAlerts call to move alerts to history index
        monitor = monitor.copy(triggers = listOf(trigger1))
        updateMonitor(monitor, monitorId)

        var alerts = listOf<Alert>()
        OpenSearchTestCase.waitUntil({
            alerts = searchAlerts(monitorId, customAlertsHistoryIndex)
            if (alerts.size == 1) {
                return@waitUntil true
            }
            return@waitUntil false
        }, 30, TimeUnit.SECONDS)
        assertEquals("Alerts from custom history index", 1, alerts.size)
    }

    fun `test search custom alerts history index after alert ack`() {
        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(index), listOf(docQuery))
        val trigger1 = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        val trigger2 = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        val customAlertsIndex = "custom_alerts_index"
        val customAlertsHistoryIndex = "custom_alerts_history_index"
        val customAlertsHistoryIndexPattern = "<custom_alerts_history_index-{now/d}-1>"
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger1, trigger2),
            dataSources = DataSources(
                alertsIndex = customAlertsIndex,
                alertsHistoryIndex = customAlertsHistoryIndex,
                alertsHistoryIndexPattern = customAlertsHistoryIndexPattern
            )
        )
        val monitorResponse = createMonitor(monitor)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(MILLIS))
        val testDoc = """{
            "message" : "This is an error from IAD region",
            "test_strict_date_time" : "$testTime",
            "test_field" : "us-west-2"
        }"""
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        indexDoc(index, "1", testDoc)
        val monitorId = monitorResponse.id
        val executeMonitorResponse = executeMonitor(monitor, monitorId, false)
        var alertsBefore = searchAlerts(monitorId, customAlertsIndex)
        Assert.assertEquals(2, alertsBefore.size)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 2)

        var alerts = listOf<Alert>()
        OpenSearchTestCase.waitUntil({
            alerts = searchAlerts(monitorId, customAlertsIndex)
            if (alerts.size == 1) {
                return@waitUntil true
            }
            return@waitUntil false
        }, 30, TimeUnit.SECONDS)
        assertEquals("Alerts from custom index", 2, alerts.size)

        val ackReq = AcknowledgeAlertRequest(monitorId, alerts.map { it.id }.toMutableList(), WriteRequest.RefreshPolicy.IMMEDIATE)
        client().execute(AlertingActions.ACKNOWLEDGE_ALERTS_ACTION_TYPE, ackReq).get()

        // verify alerts moved from alert index to alert history index
        alerts = listOf<Alert>()
        OpenSearchTestCase.waitUntil({
            alerts = searchAlerts(monitorId, customAlertsHistoryIndex)
            if (alerts.size == 1) {
                return@waitUntil true
            }
            return@waitUntil false
        }, 30, TimeUnit.SECONDS)
        assertEquals("Alerts from custom history index", 2, alerts.size)

        // verify alerts deleted from alert index
        alerts = listOf<Alert>()
        OpenSearchTestCase.waitUntil({
            alerts = searchAlerts(monitorId, customAlertsIndex)
            if (alerts.size == 1) {
                return@waitUntil true
            }
            return@waitUntil false
        }, 30, TimeUnit.SECONDS)
        assertEquals("Alerts from custom history index", 0, alerts.size)
    }

    fun `test get alerts by list of monitors containing both existent and non-existent ids`() {
        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(index), listOf(docQuery))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger),
        )
        val monitorResponse = createMonitor(monitor)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(MILLIS))
        val testDoc = """{
            "message" : "This is an error from IAD region",
            "test_strict_date_time" : "$testTime",
            "test_field" : "us-west-2"
        }"""

        monitor = monitorResponse!!.monitor

        val id = monitorResponse.id

        var monitor1 = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger),
        )
        val monitorResponse1 = createMonitor(monitor1)
        monitor1 = monitorResponse1!!.monitor
        val id1 = monitorResponse1.id
        indexDoc(index, "1", testDoc)
        executeMonitor(monitor1, id1, false)
        executeMonitor(monitor, id, false)
        val alerts = searchAlerts(id)
        assertEquals("Alert saved for test monitor", 1, alerts.size)
        val alerts1 = searchAlerts(id)
        assertEquals("Alert saved for test monitor", 1, alerts1.size)
        val table = Table("asc", "id", null, 1000, 0, "")
        var getAlertsResponse = client()
            .execute(
                AlertingActions.GET_ALERTS_ACTION_TYPE,
                GetAlertsRequest(table, "ALL", "ALL", null, null)
            )
            .get()

        Assert.assertTrue(getAlertsResponse != null)
        Assert.assertTrue(getAlertsResponse.alerts.size == 2)

        var alertsResponseForRequestWithoutCustomIndex = client()
            .execute(
                AlertingActions.GET_ALERTS_ACTION_TYPE,
                GetAlertsRequest(table, "ALL", "ALL", null, null, monitorIds = listOf(id, id1, "1", "2"))
            )
            .get()
        Assert.assertTrue(alertsResponseForRequestWithoutCustomIndex != null)
        Assert.assertTrue(alertsResponseForRequestWithoutCustomIndex.alerts.size == 2)
        val alertIds = getAlertsResponse.alerts.stream().map { alert -> alert.id }.collect(Collectors.toList())
        var getAlertsByAlertIds = client()
            .execute(
                AlertingActions.GET_ALERTS_ACTION_TYPE,
                GetAlertsRequest(table, "ALL", "ALL", null, null, alertIds = alertIds)
            )
            .get()
        Assert.assertTrue(getAlertsByAlertIds != null)
        Assert.assertTrue(getAlertsByAlertIds.alerts.size == 2)

        var getAlertsByWrongAlertIds = client()
            .execute(
                AlertingActions.GET_ALERTS_ACTION_TYPE,
                GetAlertsRequest(table, "ALL", "ALL", null, null, alertIds = listOf("1", "2"))
            )
            .get()

        Assert.assertTrue(getAlertsByWrongAlertIds != null)
        Assert.assertEquals(getAlertsByWrongAlertIds.alerts.size, 0)
    }

    fun `test queryIndex rollover and delete monitor success`() {

        val testSourceIndex = "test_source_index"
        createIndex(testSourceIndex, Settings.EMPTY)

        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(testSourceIndex), listOf(docQuery))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger)
        )
        // This doc should create close to 1000 (limit) fields in index mapping. It's easier to add mappings like this then via api
        val docPayload: StringBuilder = StringBuilder(100000)
        docPayload.append("{")
        for (i in 1..330) {
            docPayload.append(""" "id$i.somefield.somefield$i":$i,""")
        }
        docPayload.append("\"test_field\" : \"us-west-2\" }")
        indexDoc(testSourceIndex, "1", docPayload.toString())
        // Create monitor #1
        var monitorResponse = createMonitor(monitor)
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        // Execute monitor #1
        var executeMonitorResponse = executeMonitor(monitor, monitorResponse.id, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        // Create monitor #2
        var monitorResponse2 = createMonitor(monitor)
        assertFalse(monitorResponse2?.id.isNullOrEmpty())
        monitor = monitorResponse2!!.monitor
        // Insert doc #2. This one should trigger creation of alerts during monitor exec
        val testDoc = """{
            "test_field" : "us-west-2"
        }"""
        indexDoc(testSourceIndex, "2", testDoc)
        // Execute monitor #2
        var executeMonitorResponse2 = executeMonitor(monitor, monitorResponse2.id, false)
        Assert.assertEquals(executeMonitorResponse2!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse2.monitorRunResult.triggerResults.size, 1)

        refreshIndex(AlertIndices.ALERT_INDEX)
        var alerts = searchAlerts(monitorResponse2.id)
        Assert.assertTrue(alerts != null)
        Assert.assertTrue(alerts.size == 1)

        // Both monitors used same queryIndex alias. Since source index has close to limit amount of fields in mappings,
        // we expect that creation of second monitor would trigger rollover of queryIndex
        var getIndexResponse: GetIndexResponse =
            client().admin().indices().getIndex(GetIndexRequest().indices(ScheduledJob.DOC_LEVEL_QUERIES_INDEX + "*")).get()
        assertEquals(2, getIndexResponse.indices.size)
        assertEquals(DOC_LEVEL_QUERIES_INDEX + "-000001", getIndexResponse.indices[0])
        assertEquals(DOC_LEVEL_QUERIES_INDEX + "-000002", getIndexResponse.indices[1])
        // Now we'll verify that execution of both monitors still works
        indexDoc(testSourceIndex, "3", testDoc)
        // Exec Monitor #1
        executeMonitorResponse = executeMonitor(monitor, monitorResponse.id, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        refreshIndex(AlertIndices.ALERT_INDEX)
        alerts = searchAlerts(monitorResponse.id)
        Assert.assertTrue(alerts != null)
        Assert.assertTrue(alerts.size == 2)
        // Exec Monitor #2
        executeMonitorResponse = executeMonitor(monitor, monitorResponse2.id, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        refreshIndex(AlertIndices.ALERT_INDEX)
        alerts = searchAlerts(monitorResponse2.id)
        Assert.assertTrue(alerts != null)
        Assert.assertTrue(alerts.size == 2)
        // Delete monitor #1
        client().execute(
            AlertingActions.DELETE_MONITOR_ACTION_TYPE, DeleteMonitorRequest(monitorResponse.id, WriteRequest.RefreshPolicy.IMMEDIATE)
        ).get()
        // Expect first concrete queryIndex to be deleted since that one was only used by this monitor
        getIndexResponse =
            client().admin().indices().getIndex(GetIndexRequest().indices(ScheduledJob.DOC_LEVEL_QUERIES_INDEX + "*")).get()
        assertEquals(1, getIndexResponse.indices.size)
        assertEquals(ScheduledJob.DOC_LEVEL_QUERIES_INDEX + "-000002", getIndexResponse.indices[0])
        // Delete monitor #2
        client().execute(
            AlertingActions.DELETE_MONITOR_ACTION_TYPE, DeleteMonitorRequest(monitorResponse2.id, WriteRequest.RefreshPolicy.IMMEDIATE)
        ).get()
        // Expect second concrete queryIndex to be deleted since that one was only used by this monitor
        getIndexResponse =
            client().admin().indices().getIndex(GetIndexRequest().indices(ScheduledJob.DOC_LEVEL_QUERIES_INDEX + "*")).get()
        assertEquals(0, getIndexResponse.indices.size)
    }

    fun `test queryIndex rollover failure source_index field count over limit`() {

        val testSourceIndex = "test_source_index"
        createIndex(testSourceIndex, Settings.EMPTY)

        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(testSourceIndex), listOf(docQuery))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger)
        )
        // This doc should create 999 fields in mapping, only 1 field less then limit
        val docPayload: StringBuilder = StringBuilder(100000)
        docPayload.append("{")
        for (i in 1..998) {
            docPayload.append(""" "id$i":$i,""")
        }
        docPayload.append("\"test_field\" : \"us-west-2\" }")
        indexDoc(testSourceIndex, "1", docPayload.toString())
        // Create monitor and expect failure.
        // queryIndex has 3 fields in mappings initially so 999 + 3 > 1000(default limit)
        try {
            createMonitor(monitor)
        } catch (e: Exception) {
            assertTrue(e.message?.contains("can't process index [$testSourceIndex] due to field mapping limit") ?: false)
        }
    }

    fun `test queryIndex not rolling over multiple monitors`() {
        val testSourceIndex = "test_source_index"
        createIndex(testSourceIndex, Settings.EMPTY)

        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(testSourceIndex), listOf(docQuery))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger)
        )
        // Create doc with 11 fields
        val docPayload: StringBuilder = StringBuilder(1000)
        docPayload.append("{")
        for (i in 1..10) {
            docPayload.append(""" "id$i":$i,""")
        }
        docPayload.append("\"test_field\" : \"us-west-2\" }")
        indexDoc(testSourceIndex, "1", docPayload.toString())
        // Create monitor #1
        var monitorResponse = createMonitor(monitor)
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor
        // Execute monitor #1
        var executeMonitorResponse = executeMonitor(monitor, monitorResponse.id, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        // Create monitor #2
        var monitorResponse2 = createMonitor(monitor)
        assertFalse(monitorResponse2?.id.isNullOrEmpty())
        monitor = monitorResponse2!!.monitor
        // Insert doc #2. This one should trigger creation of alerts during monitor exec
        val testDoc = """{
            "test_field" : "us-west-2"
        }"""
        indexDoc(testSourceIndex, "2", testDoc)
        // Execute monitor #2
        var executeMonitorResponse2 = executeMonitor(monitor, monitorResponse2.id, false)
        Assert.assertEquals(executeMonitorResponse2!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse2.monitorRunResult.triggerResults.size, 1)

        refreshIndex(AlertIndices.ALERT_INDEX)
        var alerts = searchAlerts(monitorResponse2.id)
        Assert.assertTrue(alerts != null)
        Assert.assertTrue(alerts.size == 1)

        // Both monitors used same queryIndex. Since source index has well below limit amount of fields in mappings,
        // we expect only 1 backing queryIndex
        val getIndexResponse: GetIndexResponse =
            client().admin().indices().getIndex(GetIndexRequest().indices(ScheduledJob.DOC_LEVEL_QUERIES_INDEX + "*")).get()
        assertEquals(1, getIndexResponse.indices.size)
        // Now we'll verify that execution of both monitors work
        indexDoc(testSourceIndex, "3", testDoc)
        // Exec Monitor #1
        executeMonitorResponse = executeMonitor(monitor, monitorResponse.id, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        refreshIndex(AlertIndices.ALERT_INDEX)
        alerts = searchAlerts(monitorResponse.id)
        Assert.assertTrue(alerts != null)
        Assert.assertTrue(alerts.size == 2)
        // Exec Monitor #2
        executeMonitorResponse = executeMonitor(monitor, monitorResponse2.id, false)
        Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
        Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
        refreshIndex(AlertIndices.ALERT_INDEX)
        alerts = searchAlerts(monitorResponse2.id)
        Assert.assertTrue(alerts != null)
        Assert.assertTrue(alerts.size == 2)
    }

    /**
     * 1. Create monitor with input source_index with 900 fields in mappings - can fit 1 in queryIndex
     * 2. Update monitor and change input source_index to a new one with 900 fields in mappings
     * 3. Expect queryIndex rollover resulting in 2 backing indices
     * 4. Delete monitor and expect that all backing indices are deleted
     * */
    fun `test updating monitor no execution queryIndex rolling over`() {
        val testSourceIndex1 = "test_source_index1"
        val testSourceIndex2 = "test_source_index2"
        createIndex(testSourceIndex1, Settings.EMPTY)
        createIndex(testSourceIndex2, Settings.EMPTY)
        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(testSourceIndex1), listOf(docQuery))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger)
        )
        // This doc should create close to 1000 (limit) fields in index mapping. It's easier to add mappings like this then via api
        val docPayload: StringBuilder = StringBuilder(100000)
        docPayload.append("{")
        for (i in 1..899) {
            docPayload.append(""" "id$i":$i,""")
        }
        docPayload.append("\"test_field\" : \"us-west-2\" }")
        // Indexing docs here as an easier means to set index mappings
        indexDoc(testSourceIndex1, "1", docPayload.toString())
        indexDoc(testSourceIndex2, "1", docPayload.toString())
        // Create monitor
        var monitorResponse = createMonitor(monitor)
        assertFalse(monitorResponse?.id.isNullOrEmpty())
        monitor = monitorResponse!!.monitor

        // Update monitor and change input
        val updatedMonitor = monitor.copy(
            inputs = listOf(
                DocLevelMonitorInput("description", listOf(testSourceIndex2), listOf(docQuery))
            )
        )
        updateMonitor(updatedMonitor, updatedMonitor.id)
        assertFalse(monitorResponse?.id.isNullOrEmpty())

        // Expect queryIndex to rollover after setting new source_index with close to limit amount of fields in mappings
        var getIndexResponse: GetIndexResponse =
            client().admin().indices().getIndex(GetIndexRequest().indices(ScheduledJob.DOC_LEVEL_QUERIES_INDEX + "*")).get()
        assertEquals(2, getIndexResponse.indices.size)

        deleteMonitor(updatedMonitor.id)
        waitUntil {
            getIndexResponse =
                client().admin().indices().getIndex(GetIndexRequest().indices(ScheduledJob.DOC_LEVEL_QUERIES_INDEX + "*")).get()
            return@waitUntil getIndexResponse.indices.isEmpty()
        }
        assertEquals(0, getIndexResponse.indices.size)
    }

    fun `test queryIndex bwc when index was not an alias`() {
        createIndex(DOC_LEVEL_QUERIES_INDEX, Settings.builder().put("index.hidden", true).build())
        assertIndexExists(DOC_LEVEL_QUERIES_INDEX)

        val testSourceIndex = "test_source_index"
        createIndex(testSourceIndex, Settings.EMPTY)

        val docQuery = DocLevelQuery(query = "test_field:\"us-west-2\"", name = "3")
        val docLevelInput = DocLevelMonitorInput("description", listOf(testSourceIndex), listOf(docQuery))
        val trigger = randomDocumentLevelTrigger(condition = ALWAYS_RUN)
        var monitor = randomDocumentLevelMonitor(
            inputs = listOf(docLevelInput),
            triggers = listOf(trigger)
        )
        // This doc should create 999 fields in mapping, only 1 field less then limit
        val docPayload = "{\"test_field\" : \"us-west-2\" }"
        // Create monitor
        try {
            var monitorResponse = createMonitor(monitor)
            indexDoc(testSourceIndex, "1", docPayload)
            var executeMonitorResponse = executeMonitor(monitor, monitorResponse!!.id, false)
            Assert.assertEquals(executeMonitorResponse!!.monitorRunResult.monitorName, monitor.name)
            Assert.assertEquals(executeMonitorResponse.monitorRunResult.triggerResults.size, 1)
            refreshIndex(AlertIndices.ALERT_INDEX)
            val alerts = searchAlerts(monitorResponse.id)
            Assert.assertTrue(alerts != null)
            Assert.assertTrue(alerts.size == 1)
            // check if DOC_LEVEL_QUERIES_INDEX alias exists
            assertAliasExists(DOC_LEVEL_QUERIES_INDEX)
        } catch (e: Exception) {
            fail("Exception happend but it shouldn't!")
        }
    }
}
