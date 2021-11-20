package database.tables

import data_loader.AnalyzeInfo
import data_loader.AnalyzeResult
import data_loader.LoadingInfo
import data_loader.defaultDelimiter
import database.enums.FileCollectType
import database.enums.LoaderType
import database.extensions.executeNoReturn
import database.extensions.runReturningFirstOrNull
import database.extensions.runUpdate
import database.extensions.getListWithNulls
import database.extensions.getList
import database.extensions.useMultipleStatements
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.util.SortedMap
import database.extensions.submitQuery

object SourceTables : DbTable("source_tables"), ApiExposed {

    override val tableDisplayFields = mapOf(
        "table_name" to mapOf("editable" to "true", "sortable" to "true"),
        "file_id" to mapOf("name" to "File ID", "editable" to "true", "sortable" to "true"),
        "file_name" to mapOf("editable" to "true", "sortable" to "true"),
        "sub_table" to mapOf("editable" to "true"),
        "loader_type" to mapOf("editable" to "false"),
        "delimiter" to mapOf("editable" to "true"),
        "qualified" to mapOf("editable" to "true", "formatter" to "boolFormatter"),
        "encoding" to mapOf("editable" to "false"),
        "url" to mapOf("editable" to "true"),
        "comments" to mapOf("editable" to "true"),
        "record_count" to mapOf("editable" to "false"),
        "collect_type" to mapOf("editable" to "true"),
        "analyze" to mapOf("editable" to "true", "formatter" to "boolFormatter"),
        "load" to mapOf("editable" to "true", "formatter" to "boolFormatter"),
        "action" to mapOf("formatter" to "actionFormatter"),
    )

    override val createStatement = """
        CREATE TABLE IF NOT EXISTS public.source_tables
        (
            st_oid bigint PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
            run_id bigint NOT NULL REFERENCES public.pipeline_runs (run_id) MATCH SIMPLE
                ON UPDATE CASCADE
                ON DELETE CASCADE,
            table_name text COLLATE pg_catalog."default" NOT NULL
				CHECK (table_name ~ '^[0-9A-Z_]+$'::text),
            file_name text COLLATE pg_catalog."default" NOT NULL CHECK (file_name ~ '^.+\..+$'),
            "analyze" boolean NOT NULL DEFAULT true,
            load boolean NOT NULL DEFAULT true,
            qualified boolean NOT NULL DEFAULT false,
            encoding text COLLATE pg_catalog."default" NOT NULL DEFAULT 'utf8'::text CHECK (check_not_blank_or_empty(encoding)),
            sub_table text COLLATE pg_catalog."default" CHECK (check_not_blank_or_empty(sub_table)),
            record_count integer NOT NULL DEFAULT 0,
            file_id text COLLATE pg_catalog."default" NOT NULL CHECK (file_id ~ '^F\d+$'),
            url text COLLATE pg_catalog."default" CHECK (check_not_blank_or_empty(url)),
            comments text COLLATE pg_catalog."default" CHECK (check_not_blank_or_empty(comments)),
            collect_type file_collect_type NOT NULL,
            loader_type loader_type NOT NULL,
            delimiter character varying(1) COLLATE pg_catalog."default" CHECK (check_not_blank_or_empty(delimiter::text)),
            CONSTRAINT unique_file_id_run_id UNIQUE (run_id, file_id),
            CONSTRAINT unique_table_name_run_id UNIQUE (run_id, table_name)
        )
        WITH (
            OIDS = FALSE
        );
    """.trimIndent()

    /** API response data class for JSON serialization */
    @Serializable
    data class Record(
        @SerialName("st_oid")
        val stOid: Long,
        @SerialName("table_name")
        val tableName: String,
        @SerialName("file_id")
        val fileId: String,
        @SerialName("file_name")
        val fileName: String,
        @SerialName("sub_table")
        val subTable: String?,
        @SerialName("loader_type")
        val loaderType: String,
        @SerialName("delimiter")
        val delimiter: String?,
        @SerialName("qualified")
        val qualified: Boolean,
        @SerialName("encoding")
        val encoding: String,
        @SerialName("url")
        val url: String?,
        @SerialName("comments")
        val comments: String?,
        @SerialName("record_count")
        val recordCount: Int,
        @SerialName("collect_type")
        val collectType: String,
        @SerialName("analyze")
        val analyze: Boolean,
        @SerialName("load")
        val load: Boolean,
    )

    /**
     * Returns JSON serializable response of all source tables linked to a given [runId]. Returns an empty list when
     * no source tables are linked to the [runId]
     */
    fun getRunSourceTables(connection: Connection, runId: Long): List<Record> {
        val sql = """
            SELECT st_oid, table_name, file_id, file_name, sub_table, loader_type, delimiter, qualified, encoding, url,
                   comments, record_count, collect_type, $tableName."analyze", load
            FROM   $tableName
            WHERE  run_id = ?
        """.trimIndent()
        return connection.submitQuery(sql = sql, runId)
    }

    private fun getStatementArguments(map: Map<String, String?>): SortedMap<String, Any?> {
        return sequence<Pair<String, Any?>> {
            for ((key, value) in map.entries) {
                when (key){
                    "table_name" -> {
                        val tableName = value ?: throw IllegalArgumentException("Table name cannot be null")
                        yield(key to tableName)
                    }
                    "file_id" -> {
                        val fileId = value ?: throw IllegalArgumentException("File ID cannot be null")
                        yield(key to fileId)
                    }
                    "file_name" -> {
                        val fileName = value ?: throw IllegalArgumentException("Filename cannot be null")
                        val loaderType = LoaderType.getLoaderType(fileName)
                        if (loaderType == LoaderType.MDB || loaderType == LoaderType.Excel) {
                            val subTable = map["sub_table"] ?: throw IllegalArgumentException(
                                "Sub Table must be not null for the provided filename"
                            )
                            yield("sub_table" to subTable)
                        }
                        yield(key to fileName)
                        yield("loader_type" to loaderType.pgObject)
                    }
                    "delimiter" -> yield(key to value.takeIf { it == null || it.isNotBlank() })
                    "url" -> yield(key to value.takeIf { it == null || it.isNotBlank() })
                    "comments" -> yield(key to value.takeIf { it == null || it.isNotBlank() })
                    "collect_type" -> {
                        yield(key to FileCollectType.valueOf(value ?: "").pgObject)
                    }
                    "qualified" -> yield(key to value.equals("on"))
                    "analyze" -> yield(key to value.equals("on"))
                    "load" -> yield(key to value.equals("on"))
                }
            }
        }.toMap().toSortedMap()
    }

    /**
     * Uses [params] map to update a given record specified by the stOid provided in the map and return the stOid
     *
     * @throws IllegalArgumentException when various conditions are not met
     * - [params] does not contain runId
     * - [params] does not contain stOid
     * - the username passed does not have access to update the source tables associated with the runId
     * @throws NumberFormatException when the runId or stOid are not Long strings
     */
    fun updateSourceTable(
        connection: Connection,
        username: String,
        params: Map<String, String?>
    ): Pair<Long, Int> {
        val runId = params["runId"]
            ?.toLong()
            ?: throw IllegalArgumentException("runId must be a non-null parameter in the url")
        val stOid = params["stOid"]
            ?.toLong()
            ?: throw IllegalArgumentException("stOid must be a non-null parameter in the url")
        require(PipelineRuns.checkUserRun(connection, runId, username)) { "Username does not own the runId" }
        val sortedMap = getStatementArguments(params)
        val sql = """
            UPDATE $tableName
            SET    ${sortedMap.keys.joinToString { key -> "$key = ?" }}
            WHERE  st_oid = ?
        """.trimIndent()
        val updateCount = connection.runUpdate(sql = sql, *sortedMap.values.toTypedArray(), stOid)
        return stOid to updateCount
    }

    /**
     * Uses [params] map to insert a record into [SourceTables] and return the new records stOid
     *
     * @throws IllegalArgumentException when various conditions are not met
     * - [params] does not contain runId
     * - the username passed does not have access to update the source tables associated with the runId
     * - the insert command returns null meaning a record was not inserted
     * @throws NumberFormatException when the runId or stOid are not Long strings
     */
    fun insertSourceTable(connection: Connection, username: String, params: Map<String, String?>): Long {
        val runId = params["runId"]
            ?.toLong()
            ?: throw IllegalArgumentException("runId must be a non-null parameter in the url")
        require(PipelineRuns.checkUserRun(connection, runId, username)) { "Username does not own the runId" }
        val sortedMap = getStatementArguments(params)
        val sql = """
            INSERT INTO $tableName (run_id,${sortedMap.keys.joinToString()})
            VALUES (?,${"?,".repeat(sortedMap.size).trim(',')})
            RETURNING st_oid
        """.trimIndent()
        return connection.runReturningFirstOrNull(sql = sql, runId, *sortedMap.values.toTypedArray())
            ?: throw IllegalArgumentException("Error while trying to insert record. Null returned")
    }

    /**
     * Uses [params] map to delete a record from [SourceTables] as specified by the stOid and return the stOid
     *
     * @throws IllegalArgumentException when various conditions are not met
     * - [params] does not contain runId
     * - [params] does not contain stOid
     * - the username passed does not have access to update the source tables associated with the runId
     * @throws NumberFormatException when the runId or stOid are not Long strings
     */
    fun deleteSourceTable(connection: Connection, username: String, params: Map<String, String?>): Long {
        val runId = params["runId"]
            ?.toLong()
            ?: throw IllegalArgumentException("runId must be a non-null parameter in the url")
        val stOid = params["stOid"]
            ?.toLong()
            ?: throw IllegalArgumentException("stOid must be a non-null parameter in the url")
        require(PipelineRuns.checkUserRun(connection, runId, username)) { "Username does not own the runId" }
        connection.executeNoReturn(sql = "DELETE FROM $tableName WHERE st_oid = ?", stOid)
        return stOid
    }

    data class AnalyzeFiles(val fileName: String, val analyzeInfo: List<AnalyzeInfo>)

    fun filesToAnalyze(connection: Connection, runId: Long): List<AnalyzeFiles> {
        return connection.prepareStatement("""
            SELECT file_name,
                   array_agg(st_oid order by st_oid) st_oids,
                   array_agg(table_name order by st_oid) table_names,
                   array_agg(sub_table order by st_oid) sub_Tables,
                   array_agg(delimiter order by st_oid) "delimiter",
                   array_agg(qualified order by st_oid) qualified
            FROM   $tableName
            WHERE  run_id = ?
            AND    "analyze"
            GROUP BY file_name
        """.trimIndent()).use { statement ->
            statement.setLong(1, runId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val stOids = rs.getArray(2).getList<Long>()
                        val tableNames = rs.getArray(3).getList<String>()
                        val subTables = rs.getArray(4).getListWithNulls<String>()
                        val delimiters = rs.getArray(5).getListWithNulls<String>()
                        val qualified = rs.getArray(6).getList<Boolean>()
                        val info = stOids.mapIndexed { i, stOid ->
                            AnalyzeInfo(
                                stOid = stOid,
                                tableName = tableNames[i],
                                subTable = subTables[i],
                                delimiter = delimiters[i]?.get(i) ?: defaultDelimiter,
                                qualified = qualified[i],
                            )
                        }
                        add(AnalyzeFiles(fileName = rs.getString(1), analyzeInfo = info))
                    }
                }
            }
        }
    }

    fun finishAnalyze(connection: Connection, data: Map<Long, AnalyzeResult>) {
        val columnSql = """
            INSERT INTO ${SourceTableColumns.tableName}(st_oid,name,type,max_length,min_length,label,column_index)
            VALUES(?,?,?,?,?,'',?)
            ON CONFLICT (st_oid, name) DO UPDATE SET type = ?,
                                                     max_length = ?,
                                                     min_length = ?,
                                                     column_index = ?
        """.trimIndent()
        val tableSql = """
            UPDATE $tableName
            SET    "analyze" = false,
                   record_count = ?
            WHERE  st_oid = ?
        """.trimIndent()
        connection.useMultipleStatements(listOf(columnSql, tableSql)) { statements ->
            val columnStatement = statements.getOrNull(0)
                ?: throw IllegalStateException("Column statement must exist")
            val tableStatement = statements.getOrNull(1)
                ?: throw IllegalStateException("Table statement must exist")
            for ((stOid, analyzeResult) in data) {
                val repeats = analyzeResult.columns
                    .groupingBy { it.name }
                    .eachCount()
                    .filter { it.value > 1 }
                    .toMutableMap()
                for (column in analyzeResult.columns) {
                    val columnName = repeats[column.name]?.let { repeatCount ->
                        repeats[column.name] = repeatCount - 1
                        "${column.name}_$repeatCount"
                    } ?: column.name
                    columnStatement.setLong(1, stOid)
                    columnStatement.setString(2, columnName)
                    columnStatement.setString(3, column.type)
                    columnStatement.setInt(4, column.maxLength)
                    columnStatement.setInt(5, column.minLength)
                    columnStatement.setInt(6, column.index)
                    columnStatement.setString(7, column.type)
                    columnStatement.setInt(8, column.maxLength)
                    columnStatement.setInt(9, column.minLength)
                    columnStatement.setInt(10, column.index)
                    columnStatement.addBatch()
                }
                tableStatement.setInt(1, analyzeResult.recordCount)
                tableStatement.setLong(2, stOid)
                tableStatement.addBatch()
            }
            columnStatement.executeBatch()
            tableStatement.executeBatch()
        }
    }

    data class LoadFiles(val fileName: String, val loaders: List<LoadingInfo>)

    fun filesToLoad(connection: Connection, runId: Long): List<LoadFiles> {
        return connection.prepareStatement("""
            with t1 as (
                SELECT t1.st_oid,
                       'CREATE table '||t1.table_name||' ('||
                       STRING_AGG(t2.name::text,' text,'::text order by t2.column_index)||
                       ' text)' create_statement
                FROM   $tableName t1
                JOIN   ${SourceTableColumns.tableName} t2
                ON     t1.st_oid = t2.st_oid
                WHERE  t1.run_id = ?
                AND    t1.load
                GROUP BY t1.st_oid
            )
            SELECT t2.file_name,
                   array_agg(t1.st_oid order by t1.st_oid) st_oids,
                   array_agg(t2.table_name order by t1.st_oid) table_names,
                   array_agg(t2.sub_table order by t1.st_oid) sub_Tables,
                   array_agg(t2.delimiter order by t1.st_oid) "delimiter",
                   array_agg(t2.qualified order by t1.st_oid) qualified,
                   array_agg(t1.create_statement order by t1.st_oid) create_statements
            FROM   t1
            JOIN   $tableName t2
            ON     t1.st_oid = t2.st_oid
            GROUP BY file_name;
        """.trimIndent()).use { statement ->
            statement.setLong(1, runId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val stOids = rs.getArray(2).getList<Long>()
                        val tableNames = rs.getArray(3).getList<String>()
                        val subTables = rs.getArray(4).getListWithNulls<String>()
                        val delimiters = rs.getArray(5).getListWithNulls<String>()
                        val areQualified = rs.getArray(6).getList<Boolean>()
                        val createStatements = rs.getArray(7).getList<String>()
                        val info = stOids.mapIndexed { i, stOid ->
                            LoadingInfo(
                                stOid,
                                tableName = tableNames[i],
                                createStatement = createStatements[i],
                                delimiter = delimiters[i]?.get(i) ?: defaultDelimiter,
                                qualified = areQualified[i],
                                subTable = subTables[i],
                            )
                        }
                        add(LoadFiles(fileName = rs.getString(1), loaders = info))
                    }
                }
            }
        }
    }
}