package tasks

import database.enums.LoaderType
import database.procedures.UpdateFiles
import database.tables.PipelineRunTasks
import database.tables.SourceTables
import java.sql.Connection

/**
 * System task to validate the source table options are correct.
 *
 * Fixes any source files that are easily repaired with the [UpdateFiles] procedure then proceeds to validate that each
 * file that needs to contain a sub table has a non-null and non-blank sub table value
 *
 * Future Changes
 * --------------
 * - add more file validation steps
 */
class ValidateSourceTables(pipelineRunTaskId: Long): SystemTask(pipelineRunTaskId) {

    override val taskId: Long = 8

    override suspend fun run(connection: Connection, task: PipelineRunTasks.PipelineRunTask) {
        UpdateFiles.call(connection, task.runId)
        val sql = """
            SELECT loader_type, file_name
            FROM   ${SourceTables.tableName}
            WHERE  run_id = ?
            AND    loader_type in (?,?)
            AND    TRIM(sub_table) IS NULL
        """.trimIndent()
        val issues = connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, task.runId)
            statement.setObject(2, LoaderType.Excel.pgObject)
            statement.setObject(3, LoaderType.MDB.pgObject)
            statement.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) {
                        rs.getString(1) to rs.getString(2)
                    } else {
                        null
                    }
                }.toList()
            }
        }
        if (issues.isNotEmpty()) {
            error(issues.joinToString { "${it.first} file ${it.second} cannot have a null sub table" })
        }

    }
}