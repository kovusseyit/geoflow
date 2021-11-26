let runId = -1;
let code;

async function pickup() {
    $(`#${modalId}`).modal('hide');
    const response = await postJSON(`/api/v2/pipeline-runs/pickup/${runId}`);
    const json = await response.json();
    if ('errors' in json) {
        showToast('Error During Pickup', json.errors.map(error => `${error.error_name} -> ${error.message}`).join('\n'));
    } else {
        $(`#${tableId}`).bootstrapTable('refresh');
        showToast('Picked Up Run', json.payload);
    }
}

function showModal(value) {
    runId = value;
    $(`#${modalId}`).modal('show');
}

function enterRun(value) {
    redirect(`/tasks/${value}`);
}

function actionsFormatter(value, row) {
    if (row[`${code}_user`] === '') {
        return `<span style="display: inline;"><i class="fas fa-play p-1 inTableButton" onClick="showModal(${row.run_id})"></i></span>`;
    } else {
        return `<span style="display: inline;"><i class="fas fa-sign-in-alt p-1 inTableButton" onClick="enterRun(${row.run_id})"></i></span>`;
    }
}

$(document).ready(function() {
    const url = new URL(window.location.href);
    const urlParams = new URLSearchParams(url.search);
    code = urlParams.get('code');
});