
import Viewer from "viewerjs";

class ViewerElement extends HTMLElement {

    constructor() {
        super();
    }

    // work with imgs-html since it's monitoring `imgs-complete` event
    connectedCallback() {
        const images = this.querySelectorAll('img');
        const imagePromises = Array.from(images).map((img) => {
          return new Promise((resolve, reject) => {
            console.log(img.imgsComplete);
            if (img.imgsComplete) {
              resolve(); // The image is already loaded
            } else {
              img.addEventListener('imgs-complete', () => {
                resolve();
              });
            }
          });
        });
        Promise.all(imagePromises).then(() => {
            const viewer = new Viewer(this, {
                toolbar: {
                    zoomIn: true,
                    zoomOut: true,
                    prev: {
                        show: true,
                        size: 'large'
                    },
                    play: 0,
                    next: {
                        show: true,
                        size: 'large'
                    },
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

}

customElements.define("img-viewer", ViewerElement);