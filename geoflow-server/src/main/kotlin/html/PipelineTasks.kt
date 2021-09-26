package html

import kotlinx.html.script
import kotlinx.html.unsafe
import orm.tables.PipelineRunTasks

class PipelineTasks(runId: Long): BasePage() {
    private val taskDataModalId = "taskData"
    private val taskTableId = "tasks"
    init {
        setContent {
            autoRefreshTable(
                taskTableId,
                "/api/pipeline-run-tasks?taskId=$runId",
                PipelineRunTasks.tableDisplayFields,
                buttons = listOf("btnRun")
            )
            dataDisplayModal(
                taskDataModalId,
                "Task Details",
            )
            messageBoxModal()
        }
        setScript {
            script {
                src = "https://unpkg.com/bootstrap-table@1.18.3/dist/extensions/auto-refresh/bootstrap-table-auto-refresh.js"
            }
            postValue()
            script {
                unsafe {
                    raw("""
                        function statusFormatter(value, row) {
                            switch(value) {
                                case 'Waiting':
                                    return '';
                                case 'Scheduled':
                                    return '<i class="fa fa-arrow-circle-right"></i>';
                                case 'Running':
                                    return '<i class="fa fa-cog fa-spin"></i>';
                                case 'Complete':
                                    return '<i class="fa fa-check"></i>';
                                case 'Failed':
                                    return '<t class="fa fa-exclamation"></i>';
                            }
                        }
                        function titleCase(title) {
                            return title.replace(
                                /\w\S*/g,
                                function(txt) {
                                    return txt.charAt(0).toUpperCase() + txt.substr(1).toLowerCase();
                                }
                            ).replace(
                                'Id',
                                'ID'
                            );
                        }
                        function handleRowClick(row) {
                            let ${'$'}modalBody = $('#modalBody');
                            ${'$'}modalBody.empty();
                            for (const [key, value] of Object.entries(row)) {
                                const div = document.createElement('div');
                                const label = document.createElement('label');
                                label['for'] = key.replace(/\s+/g, '_');
                                label.innerHTML = titleCase(key.replace(/_+/g, ' '));
                                div.appendChild(label);
                                const textValue = document.createElement('p');
                                textValue.id = key.replace(/\s+/g, '_');
                                textValue.innerHTML = value === '' ? ' ' : value;
                                textValue.classList.add('border', 'rounded', 'p-3');
                                div.appendChild(textValue);
                                ${'$'}modalBody.append(div);
                            }
                            $('#$taskDataModalId').modal('toggle');
                        }
                        $('#$taskTableId').on('click-row.bs.table', (e, row, element, field) => { handleRowClick(row) });
                    """.trimIndent())
                }
            }
        }
    }
}