
function getLockKey(sourceId) {
    return "source-icon-lock-" + sourceId;
}

function getSourceIconKey(sourceId) {
    return "source-icon-" + sourceId;
}

function getRawSourceIconKey(sourceId) {
    return "raw-source-icon-" + sourceId;
}

function compareImages(images1, images2) {
    if (images1 == null && images2 == null) {
        return true;
    }
    if (images1?.length !== images2?.length) {
        return false;
    }
    for(var i = 0; i < images1.length; i++) {
        if (images1[i] !== images2[i]) {
            return false;
        }
    }
    return true;
}

function setStorageImages(key, images) {
    window.localStorage.setItem(key, JSON.stringify({'images': images}));
}

function getStorageImages(key) {
    return JSON.parse(window.localStorage.getItem(key))?.images;
}

function setSourceImages(sourceId, images) {
    navigator.locks.request(getLockKey(sourceId), () => {
        var rawSourceIcons = getStorageImages(getRawSourceIconKey(sourceId));
        /*
         If there is no valid image, try it again since last time it may because of client network error.
         There should be at least one from google website icons.
         */
        if (!compareImages(rawSourceIcons, images) || !getStorageImages(getSourceIconKey(sourceId))?.length) {
            setStorageImages(getRawSourceIconKey(sourceId), images);
            setStorageImages(getSourceIconKey(sourceId), images);
        }
    });
}

function getInitSourceImage(sourceId) {
    var images = getStorageImages(sourceId);
    if (!images?.length) {
        return "";
    }
    return images[0];
}

function updateImageDomSrc(imageDom, sourceId, event, retried) {
    navigator.locks.request(getLockKey(sourceId), () => {
        // nothing to get, return
        const images = getStorageImages(getSourceIconKey(sourceId));
        if (!images?.length) {
            // retry after 1s and hopefully folder list has been loaded
            if (!retried) {
                setTimeout(1000, () => {updateImageDomSrc(imageDom, sourceId, event, true)});
            }
            return;
        }
        // another error handler has removed the url, set the new src and skip
        if (event.target.getAttribute('src') != images[0]) {
            imageDom.setAttribute('src', images[0]);
            return;
        }
        // try the next url
        images.shift();
        if (images.length) {
            const nextUrl = images[0];
            imageDom.setAttribute('src', nextUrl);
        }
        setStorageImages(getSourceIconKey(sourceId), images);
    });
}

function addSourceIconErrorHandler(imageDom, sourceId) {
    imageDom.addEventListener("error", (event) => {
        updateImageDomSrc(imageDom, sourceId, event, false);
    });
}