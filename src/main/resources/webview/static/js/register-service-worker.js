
if('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
        navigator.serviceWorker.register('/static/js/service-worker.js')
            .then((registration) => {
                console.log(`service worker registered successfully ${registration}`)
            })
    })
} else {
    console.log(`Service worker is not supported in this browser.`)
}