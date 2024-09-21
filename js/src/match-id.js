
const POS_STEP = 1000;

function matchFromUrl(regex) {
    const url = window.location.pathname;
    const match = url.match(regex);
    if (match && match[1]) {
        return match[1];
    } else {
        return null; // or throw an error, depending on your use case
    }
}

function folderFromUrl() {
    return matchFromUrl(/\/folders\/([a-f0-9-]+)\/articles/);
}
window.folderFromUrl = folderFromUrl;

function sourceFromUrl() {
    return matchFromUrl(/\/sources\/([a-f0-9-]+)\/articles/);
}
window.sourceFromUrl = sourceFromUrl;

function getDefaultFolderID() {
    return document.querySelector('.default-folder-link').getAttribute('folder-id');
}
window.getDefaultFolderID = getDefaultFolderID;

function getSourceElements(folderID) {
    return Array.from(document.getElementById('source-under-folder-' + folderID)?.querySelectorAll('.source-menu'));
}

function getNextPositionInFolder(folderID) {
    const sourceElems = getSourceElements(folderID);
    if (!sourceElems?.length) {
        return POS_STEP;
    } else {
        const maxPos = Number(sourceElems[sourceElems.length - 1].getAttribute("pos")) ?? 0;
        return maxPos + POS_STEP;
    }
}
window.getNextPositionInFolder = getNextPositionInFolder;

function getFoldersFromDom(excludeFolderID, includeDefault) {
    var defaultFolder = [];
    if (includeDefault) {
        defaultFolder = Array.from(document.getElementsByClassName('default-folder-link')).map((e) => {
            const folderId = e.getAttribute('folder-id');
            return {
                'id': folderId,
                'position': Number(e.getAttribute('pos')),
                'name': "Root Folder",
                'nextPosition': getNextPositionInFolder(folderId),
            }
        }).filter((f) => f.id !== excludeFolderID);
    }
    var nonDefaultFolders = Array.from(document.getElementsByClassName('folder-name-link')).map((e) => {
        const folderId = e.getAttribute('folder-id');
        return {
            'id': folderId,
            'position': Number(e.getAttribute('pos')),
            'name': e.innerText,
            'nextPosition': getNextPositionInFolder(folderId),
        }
    }).filter((f) => f.id !== excludeFolderID);
    return defaultFolder.concat(nonDefaultFolders);
}
window.getFoldersFromDom = getFoldersFromDom;

function getSourcesFromFolder(folderID, excludeSourceID) {
    const sourceElems = getSourceElements(folderID);
    return sourceElems.map((e) => {
        return {
            'id': e.getAttribute('source-id'),
            'position': Number(e.getAttribute('pos')),
            'name': e.innerText,
        }
    }).filter((s) => s.id !== excludeSourceID);
}
window.getSourcesFromFolder = getSourcesFromFolder;

async function reloadFolderList(cleanupPosition) {
    if (cleanupPosition) {
        await fetch('/hx/cleanupPosition/folders', {method: 'POST'});
    }
    var result = new Promise(resolve => {
        htmx.on('#folder-list', 'load', resolve());
    })
    htmx.trigger('#folder-list', 'reload-folders', {});
    return await result;
}

async function updateFolderPosition(folderID, position) {
    await fetch(`/hx/folders/${folderID}/update`,
        {method: 'POST', headers: {"Content-Type": "application/json"},  body: `{"position": ${position}}`});
    await reloadFolderList(false);
}
window.updateFolderPosition = updateFolderPosition;

async function moveSourceAfter(folderID, sourceID, targetSourceID) {
    await fetch(`/hx/folders/${folderID}/sources/${sourceID}/moveAfter/${targetSourceID}`, {method: 'POST'});
    await reloadFolderList(false);
}
window.moveSourceAfter = moveSourceAfter;

async function moveSourceBefore(folderID, sourceID, targetSourceID) {
    await fetch(`/hx/folders/${folderID}/sources/${sourceID}/moveBefore/${targetSourceID}`, {method: 'POST'});
    await reloadFolderList(false);
}
window.moveSourceBefore = moveSourceBefore;

async function copySourceToFolder(sourceID, fromFolderID, toFolderID, toPosition) {
    await fetch(`/hx/sources/${sourceID}/copy_to_folder?to_folder_id=${toFolderID}&position=${toPosition}`,
        {method: 'POST'});
    await reloadFolderList(false);
}
window.copySourceToFolder = copySourceToFolder;

async function moveSourceToFolder(sourceID, fromFolderID, toFolderID, toPosition) {
    await fetch(`/hx/sources/${sourceID}/move_to_folder?from_folder_id=${fromFolderID}&to_folder_id=${toFolderID}&position=${toPosition}`,
        {method: 'POST'});
    window.location = `/sources/${sourceID}/articles?in_folder=${toFolderID}`;
}
window.moveSourceToFolder = moveSourceToFolder;

async function deleteSourceFromFolder(sourceID, fromFolderID) {
    await fetch(`/hx/sources/${sourceID}/delete_from_folder?from_folder_id=${fromFolderID}`, {method: 'POST'});
    window.location = "/";
}
window.deleteSourceFromFolder = deleteSourceFromFolder;

async function unsubscribeSource(sourceID) {
    await fetch(`/hx/sources/${sourceID}/unsubscribe`, {method: 'POST'});
    window.location = "/";
}
window.unsubscribeSource = unsubscribeSource;


async function getPositionAfter(folderID, retried) {
    const folders = getFoldersFromDom();
    for(var i = 0; i < folders.length; i++) {
        if (folders[i].id === folderID) {
            if (i === folders.length - 1) {
                return folders[i].position + POS_STEP;
            }
            if (folders[i+1].position <= folders[i].position + 1) {
                if (retried) {
                    throw new Error('Folder position is not cleaned');
                }
                await reloadFolderList();
                return getPositionAfter(folderID, true);
            }
            return Math.floor((folders[i+1].position + folders[i].position) / 2);
        }
    }
    throw new Error('Target folder not found when get position after ' + folderID)
}
window.getPositionAfter = getPositionAfter;


async function getPositionBefore(folderID, retried) {
    const folders = getFoldersFromDom();
    for(var i = 0; i < folders.length; i++) {
        if (folders[i].id === folderID) {
            if (i === 0 && folders[i].position > 1) {
                return Math.floor(folders[i].position / 2);
            }
            if ((i===0 && folders[i].position <= 1) || folders[i].position <= folders[i-1].position + 1) {
                if (retried) {
                    throw new Error('Folder position is not cleaned');
                }
                await reloadFolderList();
                return getPositionBefore(folderID, true);
            }
            return Math.floor((folders[i-1].position + folders[i].position) / 2);
        }
    }
    throw new Error('Target folder not found when get position after ' + folderID)
}
window.getPositionBefore = getPositionBefore;


function getNextFolderPosition() {
    const folders = getFoldersFromDom();
    const lastFolder = folders[folders.length - 1];
    return lastFolder.position + POS_STEP;
}
window.getNextFolderPosition = getNextFolderPosition;
