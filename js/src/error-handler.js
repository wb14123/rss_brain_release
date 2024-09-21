
function showErrorMessage(errMsg, details) {
    Toastify({
        text: errMsg,
        className: 'error-toast',
        duration: 30000,
        newWindow: false,
        close: true,
        gravity: 'bottom',
        position: 'center',
        stopOnFocus: true,
    }).showToast();
    console.log("Show error message details:");
    console.log(details);
}

htmx.on("htmx:responseError", (event) => {
    showErrorMessage(`Error: ${event.detail.xhr.responseText}`,{
        'url': event.detail.xhr.responseURL,
        'status': event.detail.xhr.status,
        'response': event.detail.xhr.response,
    });
});

htmx.on("htmx:sendError", (event) => {
    showErrorMessage("Failed to send network request. URL: " + event.detail.requestConfig.path);
});
