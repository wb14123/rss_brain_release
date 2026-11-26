
import Viewer from "viewerjs";

function isIOSSafari() {
    const userAgent = window.navigator.userAgent;
    const isIOS = /iP(ad|hone|od)/.test(userAgent) || (
        window.navigator.platform === 'MacIntel' && window.navigator.maxTouchPoints > 1
    );
    return isIOS && /Safari/i.test(userAgent) && !/CriOS|FxiOS|EdgiOS/i.test(userAgent);
}

class ViewerElement extends HTMLElement {

    constructor() {
        super();
        this.viewer = null;
        this.initTimer = null;
        this.destroyed = false;
    }

    // work with imgs-html since it's monitoring `imgs-complete` event
    connectedCallback() {
        this.destroyed = false;

        if (this.viewer) {
            return;
        }

        const images = this.querySelectorAll('img');
        const IMGS_TIMEOUT = 10000; // 10s timeout to prevent hanging on iOS Safari

        const imagePromises = Array.from(images).map((img) => {
          return new Promise((resolve) => {
            if (img.imgsComplete) {
              // Already loaded — resolve with the image if it has a valid src
              resolve(img.getAttribute('src') ? img : null);
            } else {
              const handler = (event) => {
                img.removeEventListener('imgs-complete', handler);
                // Only resolve with the image if it loaded successfully (detail=true)
                resolve(event.detail ? img : null);
              };
              img.addEventListener('imgs-complete', handler);
            }
          });
        });

        // Wait for all images to settle, but cap at IMGS_TIMEOUT so we never
        // hang forever on iOS Safari where loading="lazy" + programmatic src
        // may cause onload/onerror to never fire.
        const allSettled = Promise.all(imagePromises).catch(() => []);
        const withTimeout = Promise.race([
          allSettled,
          new Promise((resolve) => {
            this.initTimer = setTimeout(resolve, IMGS_TIMEOUT);
          })
        ]);

        withTimeout.then(() => {
            if (this.initTimer) {
                clearTimeout(this.initTimer);
                this.initTimer = null;
            }

            if (this.destroyed || this.viewer) {
                return;
            }

            const validImages = Array.from(images).filter((img) =>
              img.imgsComplete && img.getAttribute('src')
            );
            if (validImages.length === 0) {
                return; // No valid images, don't initialize viewer
            }

            this.viewer = new Viewer(this, {
                // Do not inherit `loading="lazy"` from the in-article images.
                // Viewer.js clones the original image into a fixed-position modal;
                // iOS Safari can leave that lazy clone blank and never fire load.
                inheritedAttributes: [
                    'crossOrigin',
                    'decoding',
                    'isMap',
                    'referrerPolicy',
                    'sizes',
                    'srcset',
                    'useMap',
                ],
                filter(image) {
                    return image.imgsComplete && image.getAttribute('src');
                },
                view(event) {
                    event.detail.image.removeAttribute('loading');
                    event.detail.image.loading = 'eager';
                },
                // iOS Safari is prone to missing transitionend after a Viewer.js
                // modal image fails to finish rendering. Make hide/show immediate
                // there so the close button cannot leave the user trapped.
                transition: !isIOSSafari(),
                ready: () => {
                    if (!this.viewer || !this.viewer.button) {
                        return;
                    }

                    this.viewer.button.addEventListener('touchend', (event) => {
                        event.preventDefault();
                        this.viewer.hide(true);
                    }, { passive: false });
                },
                toolbar: {
                    zoomIn: true,
                    zoomOut: true,
                    prev: { show: true, size: 'large' },
                    play: 0,
                    next: { show: true, size: 'large' },
                    oneToOne: true,
                    reset: true,
                    rotateLeft: 0,
                    rotateRight: 0,
                    flipHorizontal: 0,
                    flipVertical: 0,
                }
            });
        });
    }

    disconnectedCallback() {
        this.destroyed = true;

        if (this.initTimer) {
            clearTimeout(this.initTimer);
            this.initTimer = null;
        }

        if (this.viewer) {
            this.viewer.destroy();
            this.viewer = null;
        }
    }

}

customElements.define("img-viewer", ViewerElement);
