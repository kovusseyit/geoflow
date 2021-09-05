package orm.entities

import org.ktorm.entity.Entity
import java.time.LocalDate

interface PipelineRun: Entity<PipelineRun> {
    val runId: Long
    val dataSource: DataSource
    val recordDate: LocalDate
    val workflowOperation: String
    val operationState: String
    val collectionUser: InternalUser?
    val loadUser: InternalUser?
    val checkUser: InternalUser?
    val qaUser: InternalUser?
}