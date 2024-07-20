
self.addEventListener('install', (event) => {
    console.log(`installing service worker`);
})

self.addEventListener('activate', (event) => {
    console.log(`activating service worker`);
})

self.addEventListener('fetch', (event) => {
    // no offline yet
    console.log(`fetching...${event.request.url}`);
})
