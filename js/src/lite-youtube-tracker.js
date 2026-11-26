class LiteYouTubeTracker extends HTMLElement {
    constructor() {
        super();
        this.originalElement = document.createElement('lite-youtube');
        this.sendProgress = this.sendProgress.bind(this);
        this.handleVisibilityChange = this.handleVisibilityChange.bind(this);
        this.toggleBrowserSize = this.toggleBrowserSize.bind(this);
        this.isBrowserSized = false;
        this.originalStyles = {};
        this.originalIframeStyles = {};
        this.isHovering = false;
        this.playerReady = false;
        this.isTouchDevice = this.detectTouchDevice();
    }

    detectTouchDevice() {
        return (('ontouchstart' in window) ||
                (navigator.maxTouchPoints > 0) ||
                (navigator.msMaxTouchPoints > 0));
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

        // Create browser size button
        this.createBrowserSizeButton();

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

    createBrowserSizeButton() {
        // Create button
        this.browserSizeButton = document.createElement('button');
        this.browserSizeButton.innerHTML = '⛶'; // Expand icon
        this.browserSizeButton.title = 'Resize to browser size';
        this.browserSizeButton.setAttribute('aria-label', 'Resize video to browser size');

        // Style the button
        Object.assign(this.browserSizeButton.style, {
            position: 'absolute',
            bottom: '2px',
            right: '200px',
            transform: 'translateX(-50%)',
            zIndex: '1000',
            background: 'rgba(0, 0, 0, 0.7)',
            color: 'white',
            border: 'none',
            borderRadius: '4px',
            padding: '4px 4px',
            fontSize: '18px',
            cursor: 'pointer',
            transition: 'opacity 0.3s ease, background-color 0.2s ease',
            fontFamily: 'Arial, sans-serif',
            opacity: '0',
            pointerEvents: 'none'
        });

        // Add hover effect for background
        this.browserSizeButton.addEventListener('mouseenter', () => {
            this.browserSizeButton.style.background = 'rgba(0, 0, 0, 0.9)';
        });

        this.browserSizeButton.addEventListener('mouseleave', () => {
            this.browserSizeButton.style.background = 'rgba(0, 0, 0, 0.7)';
        });

        // Add click handler
        this.browserSizeButton.addEventListener('click', this.toggleBrowserSize);

        // Make the original element container positioned relative
        this.originalElement.style.position = 'relative';

        // Add hover listeners to the video container
        this.originalElement.addEventListener('mouseenter', () => {
            this.isHovering = true;
            this.updateButtonVisibilitySafe();
        });

        this.originalElement.addEventListener('mouseleave', () => {
            this.isHovering = false;
            this.updateButtonVisibilitySafe();
        });

        // Add button to the original element
        this.originalElement.appendChild(this.browserSizeButton);

        // Don't call updateButtonVisibility() here - wait for player to be ready
    }

    // Add a safe wrapper that only calls updateButtonVisibility if player is ready
    updateButtonVisibilitySafe() {
        if (this.playerReady) {
            this.updateButtonVisibility();
        }
    }

    async updateButtonVisibility() {
        // Add a guard to prevent calling this before player is ready
        if (!this.playerReady) {
            return;
        }

        let isVideoPlaying = false;

        try {
            const player = await this.originalElement.getYTPlayer();
            const playerState = player.getPlayerState();
            // Video is considered "playing" only when state is 1 (playing)
            isVideoPlaying = (playerState === 1);
        } catch (error) {
            // If we can't get the player or state, assume not playing
            // This handles cases where the video hasn't loaded yet
        }

        let shouldShow;
        
        if (this.isTouchDevice) {
            // On touch devices, only show when video is not playing
            shouldShow = !isVideoPlaying;
        } else {
            // On desktop, show when hovering or when video is not playing
            shouldShow = this.isHovering || !isVideoPlaying;
        }

        if (shouldShow) {
            this.browserSizeButton.style.opacity = '1';
            this.browserSizeButton.style.pointerEvents = 'auto';
        } else {
            this.browserSizeButton.style.opacity = '0';
            this.browserSizeButton.style.pointerEvents = 'none';
        }
    }

    toggleBrowserSize() {
        if (!this.isBrowserSized) {
            // Store original styles for the container
            this.originalStyles = {
                position: this.originalElement.style.position || '',
                top: this.originalElement.style.top || '',
                left: this.originalElement.style.left || '',
                width: this.originalElement.style.width || '',
                height: this.originalElement.style.height || '',
                zIndex: this.originalElement.style.zIndex || '',
                backgroundColor: this.originalElement.style.backgroundColor || '',
                maxWidth: this.originalElement.style.maxWidth || '',
                aspectRatio: this.originalElement.style.aspectRatio || ''
            };

            // Find and store original iframe styles
            const iframe = this.originalElement.querySelector('iframe');
            if (iframe) {
                this.originalIframeStyles = {
                    width: iframe.style.width || '',
                    height: iframe.style.height || '',
                    maxWidth: iframe.style.maxWidth || '',
                    aspectRatio: iframe.style.aspectRatio || ''
                };
            }

            // Apply browser-sized styles to container
            Object.assign(this.originalElement.style, {
                position: 'fixed',
                top: '0',
                left: '0',
                width: '100vw !important',
                height: '100vh !important',
                maxWidth: 'none',
                aspectRatio: 'unset',
                zIndex: '9999',
                backgroundColor: '#000'
            });

            // Apply browser-sized styles to iframe if it exists
            if (iframe) {
                Object.assign(iframe.style, {
                    width: '100% !important',
                    height: '100% !important',
                    maxWidth: 'none',
                    aspectRatio: 'unset'
                });
            }

            // Add CSS to override any conflicting styles
            this.addOverrideStyles();

            // this.browserSizeButton.innerHTML = '⛝'; // Compress icon
            this.browserSizeButton.title = 'Exit browser size';
            this.isBrowserSized = true;

            // Add escape key listener
            document.addEventListener('keydown', this.handleEscapeKey);
        } else {
            this.exitBrowserSize();
        }
    }

    addOverrideStyles() {
        // Create a style element to override any conflicting CSS
        this.overrideStyle = document.createElement('style');
        this.overrideStyle.textContent = `
            lite-youtube[style*="position: fixed"] {
                width: 100vw !important;
                height: 100vh !important;
                max-width: none !important;
                aspect-ratio: unset !important;
            }
            lite-youtube[style*="position: fixed"] iframe {
                width: 100% !important;
                height: 100% !important;
                max-width: none !important;
                aspect-ratio: unset !important;
            }
        `;
        document.head.appendChild(this.overrideStyle);
    }

    exitBrowserSize() {
        // Restore original styles for container
        Object.assign(this.originalElement.style, this.originalStyles);

        // Restore original iframe styles
        const iframe = this.originalElement.querySelector('iframe');
        if (iframe && this.originalIframeStyles) {
            Object.assign(iframe.style, this.originalIframeStyles);
        }

        // Remove override styles
        if (this.overrideStyle) {
            this.overrideStyle.remove();
            this.overrideStyle = null;
        }

        this.browserSizeButton.innerHTML = '⛶'; // Expand icon
        this.browserSizeButton.title = 'Resize to browser size';
        this.isBrowserSized = false;

        // Remove escape key listener
        document.removeEventListener('keydown', this.handleEscapeKey);
    }

    handleEscapeKey = (event) => {
        if (event.key === 'Escape' && this.isBrowserSized) {
            this.exitBrowserSize();
        }
    }

    async setupTracking() {
        window.addEventListener('beforeunload', this.sendProgress);
        window.addEventListener('pagehide', this.sendProgress);
        document.addEventListener('visibilitychange', this.handleVisibilityChange);

        const player = await this.originalElement.getYTPlayer();

        // Mark player as ready
        this.playerReady = true;

        player.addEventListener('onStateChange', (event) => {
            // Update button visibility when player state changes
            this.updateButtonVisibility();

            if (event.data === 2) this.sendProgress(); // Paused
        });

        // Initial visibility update now that the player is ready
        this.updateButtonVisibility();
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
        document.removeEventListener('keydown', this.handleEscapeKey);

        // Clean up override styles
        if (this.overrideStyle) {
            this.overrideStyle.remove();
        }
    }
}

// Register the component as a new element
window.customElements.define('lite-youtube-tracker', LiteYouTubeTracker);