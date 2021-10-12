import auth.UserSession
import html.*
import io.ktor.application.*
import io.ktor.html.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.sessions.*
import io.ktor.util.*
import jobs.SystemJob
import orm.enums.TaskRunType
import orm.enums.TaskStatus
import orm.tables.*

fun Route.index() {
    get("/") {
        call.respondRedirect("/index")
    }
    route("/index") {
        get {
            call.respondHtml {
                index()
            }
        }
        post {
            val params = call.receiveParameters()
            val href = params["href"] ?: "/index"
            call.respondRedirect(href)
        }
    }
}

fun Route.pipelineStatus() = route("/pipeline-status") {
    get {
        val code = call.request.queryParameters["code"] ?: ""
        call.respondHtml {
            if (call.sessions.get<UserSession>()!!.hasRole(code)) {
                pipelineStatus(code)
            } else {
                accessRestricted(code)
            }
        }
    }
    post {
        val params = call.receiveParameters()
        val pickup = params["pickup"] ?: ""
        val runId = params["run_id"] ?: ""
        val redirect = if (pickup.isEmpty()) {
            "/tasks?runId=$runId"
        } else {
            runCatching {
                PipelineRuns.pickupRun(
                    runId.toLong(),
                    call.sessions.get<UserSession>()!!.userId
                )
                "/tasks?runId=$runId"
            }.getOrElse { t ->
                call.application.environment.log.error("/pipeline-stats: pickup", t)
                url {
                    path("/invalid-parameter")
                    parametersOf("message", "Error: Could not pickup run")
                }
            }
        }
        call.respondRedirect(redirect)
    }
}

fun Route.pipelineTasks() = route("/tasks") {
    get {
        val user = call.sessions.get<UserSession>()!!
        val runId = call.request.queryParameters["runId"]?.toLong() ?: 0
        val run = PipelineRuns.getRun(runId)
        val runUsernames = listOf(
            run?.collectionUser?.username,
            run?.loadUser?.username,
            run?.checkUser?.username,
            run?.qaUser?.username,
        ).mapNotNull { it }
        when {
            run == null -> call.respondRedirect {
                path("invalid-parameter")
                parametersOf("message", "Run ID provided cannot be found")
            }
            user.hasRole("admin") || user.username in runUsernames -> {
                call.respondHtml { pipelineTasks(runId) }
            }
            else -> call.respondRedirect {
                path("invalid-parameter")
                parametersOf("message", "You must be a part of this run to view it")
            }
        }
    }
}

fun Route.invalidParameter() {
    get("/invalid-parameter") {
        call.respondHtml {
            invalidParameter(call.request.queryParameters["message"] ?: "")
        }
    }
}

fun Route.api() = route("/api") {
    get("/operations") {
        val operations = runCatching {
            WorkflowOperations.userOperations(call.sessions.get<UserSession>()?.roles!!)
        }.getOrElse { t ->
            call.application.environment.log.error("/api/operations", t)
            listOf()
        }
        call.respond(operations)
    }
    get("/actions") {
        val actions = runCatching {
            Actions.userActions(call.sessions.get<UserSession>()?.roles!!)
        }.getOrElse { t ->
            call.application.environment.log.error("/api/actions", t)
            listOf()
        }
        call.respond(actions)
    }
    get("/pipeline-runs") {
        val runs = runCatching {
            PipelineRuns.userRuns(
                call.sessions.get<UserSession>()?.userId!!,
                call.request.queryParameters["code"] ?: ""
            )
        }.getOrElse { t ->
            call.application.environment.log.error("/api/pipeline-runs", t)
            listOf()
        }
        call.respond(runs)
    }
    get("/pipeline-run-tasks") {
        val tasks = runCatching {
            PipelineRunTasks.getOrderedTasks(call.request.queryParameters["runId"]?.toLong() ?: 0)
        }.getOrElse { t ->
            call.application.environment.log.error("/api/pipeline-run-tasks", t)
            listOf()
        }
        call.respond(tasks)
    }
    post("/run-task") {
        val user = call.sessions.get<UserSession>()!!
        val runId = call.request.queryParameters["runId"]?.toLong() ?: 0
        val pipelineRunTaskId = call.request.queryParameters["prTaskId"]?.toLong() ?: 0
        val response = runCatching {
            val pipelineRunTask = PipelineRunTasks.getRecordForRun(user.username, runId, pipelineRunTaskId)
            if (pipelineRunTask.task.taskRunType == TaskRunType.User) {
                getUserPipelineTask(pipelineRunTask.pipelineRunTaskId, pipelineRunTask.task.taskClassName)
                    .runTask()
                mapOf("success" to "Completed ${pipelineRunTask.pipelineRunTaskId}")
            } else {
                PipelineRunTasks.setStatus(pipelineRunTask.pipelineRunTaskId, TaskStatus.Scheduled)
                kjob.schedule(SystemJob) {
                    props[it.pipelineRunTaskId] = pipelineRunTask.pipelineRunTaskId
                    props[it.runId] = pipelineRunTask.runId
                    props[it.taskClassName] = pipelineRunTask.task.taskClassName
                    props[it.runNext] = false
                }
                mapOf("success" to "Scheduled ${pipelineRunTask.pipelineRunTaskId}")
            }
        }.getOrElse { t ->
            call.application.environment.log.info("/api/run-task", t)
            mapOf("error" to t.message)
        }
        call.respond(response)
    }
    post("/run-all") {
        val user = call.sessions.get<UserSession>()!!
        val runId = call.request.queryParameters["runId"]?.toLong() ?: 0
        val pipelineRunTaskId = call.request.queryParameters["prTaskId"]?.toLong() ?: 0
        val response = runCatching {
            val pipelineRunTask = PipelineRunTasks.getRecordForRun(user.username, runId, pipelineRunTaskId)
            if (pipelineRunTask.task.taskRunType == TaskRunType.User) {
                getUserPipelineTask(pipelineRunTask.pipelineRunTaskId, pipelineRunTask.task.taskClassName)
                    .runTask()
                mapOf("success" to "Completed ${pipelineRunTask.pipelineRunTaskId}")
            } else {
                PipelineRunTasks.setStatus(pipelineRunTask.pipelineRunTaskId, TaskStatus.Scheduled)
                kjob.schedule(SystemJob) {
                    props[it.pipelineRunTaskId] = pipelineRunTask.pipelineRunTaskId
                    props[it.runId] = pipelineRunTask.runId
                    props[it.taskClassName] = pipelineRunTask.task.taskClassName
                    props[it.runNext] = true
                }
                mapOf("success" to "Scheduled ${pipelineRunTask.pipelineRunTaskId}")
            }
        }.getOrElse { t ->
            call.application.environment.log.info("/api/run-task", t)
            mapOf("error" to t.message)
        }
        call.respond(response)
    }
    post("/reset-task") {
        val user = call.sessions.get<UserSession>()!!
        val runId = call.request.queryParameters["runId"]?.toLong() ?: 0
        val pipelineRunTaskId = call.request.queryParameters["prTaskId"]?.toLong() ?: 0
        val response = runCatching {
            PipelineRunTasks.resetRecord(user.username, runId, pipelineRunTaskId)
            mapOf("success" to "Reset $pipelineRunTaskId")
        }.getOrElse { t ->
            call.application.environment.log.info("/api/run-task", t)
            mapOf("error" to t.message)
        }
        call.respond(response)
    }
    get("/task-status") {
        val pipelineRunTaskId = call.request.queryParameters["prTaskId"]?.toLong() ?: 0
        val status = PipelineRunTasks.getStatus(pipelineRunTaskId)
        call.respond(mapOf("status" to status))
    }
    route("/source-tables") {
        get {
            val runId = call.request.queryParameters["runId"]?.toLong() ?: 0
            val response = runCatching {
                SourceTables.getRunSourceTables(runId)
            }.getOrElse { t ->
                call.application.environment.log.info("/api/source-tables", t)
                listOf()
            }
            call.respond(response)
        }
        patch {
            val user = call.sessions.get<UserSession>()!!
            val params = call.request.queryParameters.names().associateWith { call.request.queryParameters[it] }
            val response = runCatching {
                val stOid = SourceTables.updateSourceTable(user.username, params)
                mapOf("success" to "updated stOid $stOid")
            }.getOrElse { t ->
                call.application.environment.log.info("/api/source-tables", t)
                val message = "Failed to update stOid ${params["stOid"]}"
                mapOf("error" to "$message. ${t.message}")
            }
            call.respond(response)
        }
        post {
            val user = call.sessions.get<UserSession>()!!
            val params = call.request.queryParameters.names().associateWith { call.request.queryParameters[it] }
            val response = runCatching {
                val stOid = SourceTables.insertSourceTable(user.username, params)
                mapOf("success" to "inserted stOid $stOid")
            }.getOrElse { t ->
                call.application.environment.log.info("/api/source-tables", t)
                val message = "Failed to insert new source table"
                mapOf("error" to "$message. ${t.message}")
            }
            call.respond(response)
        }
        delete {
            val user = call.sessions.get<UserSession>()!!
            val params = call.request.queryParameters.names().associateWith { call.request.queryParameters[it] }
            val response = runCatching {
                val stOid = SourceTables.deleteSourceTable(user.username, params)
                mapOf("success" to "deleted stOid $stOid")
            }.getOrElse { t ->
                call.application.environment.log.info("/api/source-tables", t)
                val message = "Failed to delete stOid ${params["stOid"]}"
                mapOf("error" to "$message. ${t.message}")
            }
            call.respond(response)
        }
    }
}

fun Route.js() {
    static("assets") {
        resources("javascript")
    }
}