import auth.UserSession
import html.*
import io.ktor.application.*
import io.ktor.html.*
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

/** Entry route that handles empty an empty path or the index route. Empty routes are redirected to index. */
fun Route.index() {
    get("/") {
        call.respondRedirect("/index")
    }
    get("/index") {
        call.respondHtml {
            index()
        }
    }
}

/**
 * Pipeline status route that handles all workflow code values.
 *
 * For GET requests, the current user must be authorized to access the workflow code specified in the request. For POST
 * requests, the user is trying to pick up the run specified. Tries to pick up run and throws error (after logging)
 * if operation cannot complete successfully. If successful, the user if redirected to appropriate route.
 */
fun Route.pipelineStatus() = route("/pipeline-status/{code}") {
    get {
        val code = call.parameters.getOrFail("code")
        require(call.sessions.get<UserSession>()!!.hasRole(code)) {
            UnauthorizedRouteAccessException(call.request.uri)
        }
        call.respondHtml {
            pipelineStatus(code)
        }
    }
    post {
        val params = call.receiveParameters()
        val runId = params.getOrFail("run_id").toLong()
        try {
            PipelineRuns.pickupRun(
                runId,
                call.sessions.get<UserSession>()!!.userId
            )
        } catch (t: Throwable) {
            call.application.environment.log.error("/pipeline-stats: pickup", t)
            throw t
        }
        call.respondRedirect("/tasks/$runId")
    }
}

/** Pipeline tasks route that handles request to view a specific run's task list. */
fun Route.pipelineTasks() = route("/tasks/{runId}") {
    get {
        call.respondHtml {
            pipelineTasks(call.parameters.getOrFail("runId").toLong())
        }
    }
}

/** Base API route. This will be moved to a new module/service later in development */
fun Route.api() = route("/api") {
    /** Returns list of operations based upon the current user's roles. */
    get("/operations") {
        val operations = runCatching {
            WorkflowOperations.userOperations(call.sessions.get<UserSession>()?.roles!!)
        }.getOrElse { t ->
            call.application.environment.log.error("/api/operations", t)
            listOf()
        }
        call.respond(operations)
    }
    /** Returns list of actions based upon the current user's roles. */
    get("/actions") {
        val actions = runCatching {
            Actions.userActions(call.sessions.get<UserSession>()?.roles!!)
        }.getOrElse { t ->
            call.application.environment.log.error("/api/actions", t)
            listOf()
        }
        call.respond(actions)
    }
    /** Returns list of pipeline runs for the given workflow code based upon the current user. */
    get("/pipeline-runs/{code}") {
        val runs = runCatching {
            PipelineRuns.userRuns(
                call.sessions.get<UserSession>()?.userId!!,
                call.parameters.getOrFail("code")
            )
        }.getOrElse { t ->
            call.application.environment.log.error("/api/pipeline-runs", t)
            listOf()
        }
        call.respond(runs)
    }
    /** Returns list of pipeline tasks for the given run. */
    get("/pipeline-run-tasks/{runId}") {
        val tasks = runCatching {
            PipelineRunTasks.getOrderedTasks(call.parameters.getOrFail("runId").toLong())
        }.getOrElse { t ->
            call.application.environment.log.error("/api/pipeline-run-tasks", t)
            listOf()
        }
        call.respond(tasks)
    }
    /**
     * Tries to run a specified pipeline task based upon the pipeline run task ID provided.
     *
     * If the underlining task is a User task then it is run right away and the response is a completed message. If the
     * underlining task is a System task then it is scheduled to run and the response is a scheduled message. During the
     * task fetching and assessment, an error can be thrown. The error is caught, logged and the response becomes an
     * error message.
     */
    post("/run-task/{runId}/{prTaskId}") {
        val user = call.sessions.get<UserSession>()!!
        val runId = call.parameters.getOrFail("runId").toLong()
        val pipelineRunTaskId = call.parameters.getOrFail("prTaskId").toLong()
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
    /**
     * Tries to run a specified pipeline task based upon the pipeline run task ID provided while continuing to run
     * subsequent tasks until a user task appears or the current running task fail.
     *
     * If the underlining task is a User task then it is run right away and the response is a completed message. If the
     * underlining task is a System task then it is scheduled to run and the response is a scheduled message. The prop
     * of 'runNext' is also set to true to tell the worker to continue running tasks after successful completion.
     * During the task fetching and assessment, an error can be thrown. The error is caught, logged and the response
     * becomes an error message.
     */
    post("/run-all/{runId}/{prTaskId}") {
        val user = call.sessions.get<UserSession>()!!
        val runId = call.parameters.getOrFail("runId").toLong()
        val pipelineRunTaskId = call.parameters.getOrFail("prTaskId").toLong()
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
    /** Resets the provided pipeline run task to a waiting state and deletes all child tasks if any exist. */
    post("/reset-task/{runId}/{prTaskId}") {
        val user = call.sessions.get<UserSession>()!!
        val runId = call.parameters.getOrFail("runId").toLong()
        val pipelineRunTaskId = call.parameters.getOrFail("prTaskId").toLong()
        val response = runCatching {
            PipelineRunTasks.resetRecord(user.username, runId, pipelineRunTaskId)
            mapOf("success" to "Reset $pipelineRunTaskId")
        }.getOrElse { t ->
            call.application.environment.log.info("/api/run-task", t)
            mapOf("error" to t.message)
        }
        call.respond(response)
    }
    /** Source tables route */
    route("/source-tables") {
        /** Returns all source table records for the provided pipeline run */
        get("/{runId}") {
            val runId = call.parameters.getOrFail("runId").toLong()
            val response = runCatching {
                SourceTables.getRunSourceTables(runId)
            }.getOrElse { t ->
                call.application.environment.log.info("/api/source-tables", t)
                listOf()
            }
            call.respond(response)
        }
        /** Updates a source table record (specified by the stOid) with the provided parameters */
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
        /** Creates a new source table record with the provided parameters */
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
        /** Deletes an existing source table record with the provided stOid */
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

/** WebSocket routes used in pub/sub pattern. */
fun Route.sockets() = route("/sockets") {
    /**  */
    publisher("/pipeline-run-tasks", "pipelineRunTasks")
}

/** Route for static Javascript assets */
fun Route.js() {
    static("assets") {
        resources("javascript")
    }
}