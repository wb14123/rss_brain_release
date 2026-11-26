var modalIsOpen = false;
var prevBodyOverflow = '';
var previouslyFocused = null;
var escKeyHandler = function (e) {
    if (e.key === 'Escape') {
        closeErrorDetailModal();
    }
};

var focusTrapHandler = function (e) {
    if (e.key !== 'Tab') return;

    var modal = document.querySelector('.error-detail-modal');
    if (!modal) return;

    var focusable = modal.querySelectorAll(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
    );
    if (focusable.length === 0) return;

    var first = focusable[0];
    var last = focusable[focusable.length - 1];

    if (e.shiftKey) {
        if (document.activeElement === first) {
            e.preventDefault();
            last.focus();
        }
    } else {
        if (document.activeElement === last) {
            e.preventDefault();
            first.focus();
        }
    }
};

const STATUS_MAP = {
    400: '400 Bad Request',
    401: '401 Unauthorized',
    403: '403 Forbidden',
    404: '404 Not Found',
    405: '405 Method Not Allowed',
    408: '408 Request Timeout',
    409: '409 Conflict',
    422: '422 Unprocessable Entity',
    429: '429 Too Many Requests',
    500: '500 Internal Server Error',
    502: '502 Bad Gateway',
    503: '503 Service Unavailable',
    504: '504 Gateway Timeout',
};

function getStatusText(status) {
    if (status == null) return 'N/A';
    return STATUS_MAP[status] || String(status);
}

function showErrorMessage(errMsg, details) {
    var toastNode = document.createElement('div');
    toastNode.className = 'toast-content';

    var summarySpan = document.createElement('span');
    summarySpan.className = 'toast-text';
    summarySpan.textContent = (errMsg || '').substring(0, 100);

    toastNode.appendChild(summarySpan);

    if (details) {
        var detailsBtn = document.createElement('button');
        detailsBtn.className = 'toast-details-btn';
        detailsBtn.textContent = 'Details';
        detailsBtn.addEventListener('click', function () {
            openErrorDetailModal(details);
        });
        toastNode.appendChild(detailsBtn);
    }

    Toastify({
        node: toastNode,
        className: 'error-toast',
        duration: 30000,
        newWindow: false,
        close: true,
        gravity: 'bottom',
        position: 'center',
        stopOnFocus: true,
    }).showToast();
}

function openErrorDetailModal(details) {
    details = details || {};
    // Close existing modal if already open (clean up before reuse)
    closeErrorDetailModal();

    var overlay = document.createElement('div');
    overlay.id = 'error-detail-overlay';
    overlay.className = 'error-detail-overlay';

    // Modal panel
    var modal = document.createElement('div');
    modal.className = 'error-detail-modal';
    modal.setAttribute('role', 'dialog');
    modal.setAttribute('aria-modal', 'true');
    modal.setAttribute('aria-labelledby', 'error-detail-title');
    modal.setAttribute('tabindex', '-1');

    // Header
    var header = document.createElement('div');
    header.className = 'error-detail-modal-header';

    var title = document.createElement('h3');
    title.id = 'error-detail-title';
    title.textContent = 'Error Details';

    var closeBtn = document.createElement('button');
    closeBtn.className = 'error-detail-close';
    closeBtn.setAttribute('aria-label', 'Close');
    closeBtn.textContent = '\u2715'; // ✕
    closeBtn.addEventListener('click', closeErrorDetailModal);

    header.appendChild(title);
    header.appendChild(closeBtn);

    // Body
    var body = document.createElement('div');
    body.className = 'error-detail-modal-body';

    // Helper: field with label + value
    function field(label, value) {
        var div = document.createElement('div');
        div.className = 'error-detail-field';

        var labelEl = document.createElement('div');
        labelEl.className = 'error-detail-field-label';
        labelEl.textContent = label;

        var valueEl = document.createElement('div');
        valueEl.className = 'error-detail-field-value';
        valueEl.textContent = value ?? '';

        div.appendChild(labelEl);
        div.appendChild(valueEl);
        return div;
    }

    body.appendChild(field('URL:', details.url || ''));
    body.appendChild(field('Status:', getStatusText(details.status)));

    var responseSection = document.createElement('div');
    responseSection.className = 'error-detail-field';

    var responseHeading = document.createElement('div');
    responseHeading.className = 'error-detail-field-label';
    responseHeading.textContent = 'Response body:';
    responseSection.appendChild(responseHeading);

    var pre = document.createElement('pre');
    pre.className = 'error-detail-pre';
    var r = details.response;
    if (r == null) {
        pre.textContent = '';
    } else if (typeof r === 'string') {
        pre.textContent = r;
    } else {
        try {
            pre.textContent = JSON.stringify(r, null, 2);
        } catch (e) {
            pre.textContent = '[Unable to stringify response]';
        }
    }
    responseSection.appendChild(pre);
    body.appendChild(responseSection);

    // Footer
    var footer = document.createElement('div');
    footer.className = 'error-detail-modal-footer';

    var footerCloseBtn = document.createElement('button');
    footerCloseBtn.className = 'error-detail-btn';
    footerCloseBtn.textContent = 'Close';
    footerCloseBtn.addEventListener('click', closeErrorDetailModal);

    footer.appendChild(footerCloseBtn);

    // Assemble modal
    modal.appendChild(header);
    modal.appendChild(body);
    modal.appendChild(footer);
    overlay.appendChild(modal);

    // Click backdrop to close
    overlay.addEventListener('click', function (e) {
        if (e.target === overlay) {
            closeErrorDetailModal();
        }
    });

    // Append to body
    // Save previously focused element to restore on close
    previouslyFocused = document.activeElement;
    document.body.appendChild(overlay);

    // Lock body scroll (save previous value so we can restore it later)
    prevBodyOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    modalIsOpen = true;

    // Move focus into the modal
    modal.focus();

    // ESC key listener
    document.addEventListener('keydown', escKeyHandler);
    document.addEventListener('keydown', focusTrapHandler);
}

function closeErrorDetailModal() {
    var overlay = document.getElementById('error-detail-overlay');
    if (overlay) {
        overlay.remove();
    }

    // Remove ESC key listener
    document.removeEventListener('keydown', escKeyHandler);
    document.removeEventListener('keydown', focusTrapHandler);

    // Restore body scroll to its previous value only if modal was open
    if (modalIsOpen) {
        document.body.style.overflow = prevBodyOverflow;
        modalIsOpen = false;
    }

    // Restore focus to the element that had focus before the modal opened
    if (previouslyFocused && document.contains(previouslyFocused) && typeof previouslyFocused.focus === 'function') {
        previouslyFocused.focus();
    }
    previouslyFocused = null;
}

htmx.on("htmx:responseError", function (event) {
    showErrorMessage('Error: ' + (event.detail.xhr.responseText || ''), {
        'url': event.detail.xhr.responseURL,
        'status': event.detail.xhr.status,
        'response': event.detail.xhr.response,
    });
});

htmx.on("htmx:sendError", function (event) {
    showErrorMessage("Failed to send network request. URL: " + event.detail.requestConfig.path);
});
