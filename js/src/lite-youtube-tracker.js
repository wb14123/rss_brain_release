class LiteYouTubeTracker extends HTMLElement {
    constructor() {
        super();
        this.originalElement = document.createElement('lite-youtube');
        this.sendProgress = this.sendProgress.bind(this);
        this.handleVisibilityChange = this.handleVisibilityChange.bind(this);
    }

    connectedCallback() {
        // Clone all attributes to the original element
        Array.from(this.attributes).forEach(attr => {
            this.originalElement.setAttribute(attr.name, attr.value);
        });

        // Move children to the original element
        while (this.firstChild) {
            this.originalElement.appendChild(this.firstChild);
        }

        // Replace ourselves with the original element
        this.replaceWith(this.originalElement);

        // Wait for both the element and YouTube API to be ready
        Promise.all([
            customElements.whenDefined('lite-youtube'),
            new Promise(resolve => {
                if (window.YT) return resolve();
                window.onYouTubeIframeAPIReady = resolve;
            })
        ]).then(() => {
            this.setupTracking();
            this.restoreProgress();
        });
    }

    async setupTracking() {
        window.addEventListener('beforeunload', this.sendProgress);
        window.addEventListener('pagehide', this.sendProgress);
        document.addEventListener('visibilitychange', this.handleVisibilityChange);

        const player = await this.originalElement.getYTPlayer();

        player.addEventListener('onStateChange', (event) => {
            if (event.data === 2) this.sendProgress(); // Paused
        });
    }

    async restoreProgress() {
        const savedTime = this.originalElement.getAttribute('saved-progress');
        if (!savedTime) return;

        try {
            const player = await this.originalElement.getYTPlayer();
            const time = parseFloat(savedTime) / 1000;
            if (!isNaN(time)) player.seekTo(time);
        } catch (error) {
            console.error('Progress restoration retrying...');
            setTimeout(() => this.restoreProgress(), 500);
        }
    }

    handleVisibilityChange() {
        if (document.visibilityState === 'hidden') {
            this.sendProgress();
        }
    }

    async sendProgress() {
        try {
            const player = await this.originalElement.getYTPlayer();
            const currentTime = Math.floor(await player.getCurrentTime() * 1000);
            const dataUrl = this.originalElement.getAttribute('save-progress-url');

            if (dataUrl) {
                navigator.sendBeacon(`${dataUrl}${currentTime}`);
            }
        } catch (error) {
            console.error('Progress save failed:', error);
        }
    }

    disconnectedCallback() {
        window.removeEventListener('beforeunload', this.sendProgress);
        window.removeEventListener('pagehide', this.sendProgress);
        document.removeEventListener('visibilitychange', this.handleVisibilityChange);
    }
}

// Register the component as a new element
window.customElements.define('lite-youtube-tracker', LiteYouTubeTracker);