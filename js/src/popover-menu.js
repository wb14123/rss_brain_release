

class PopoverMenu extends HTMLElement {

    #self = this;

    static observedAttributes = [
        "button-selector", // the selector that specific the button element
        "placement", // which direction to open the pop over

        // middlewares
        "flip", // whether to flip the placement if no enough space,
        "shift", // whehter to shift the placement if no enough space,

        "open-event", // the event to trigger the open the pop over
        "open-delay", // the delay to open the pop over

        /*
        What event to trigger the popover close. Options:

        * "click-content": when click the content
        * "click-button": when click the button
        * "click-other": when click on anything else than the content and button
        * "mouseleave": when mouse left both the button and content
        
        There can be multiple events at the same time, seperate by space.
        */
        "close-event",

        /*
        The delay to close the pop over after mouseleave.
        If mouse re-enters the element, the close event will be ignored.
        */
        "mouse-leave-delay",

        /* set the display style for content. Override the display attribute if defined. */
        "content-display",
    ];

    #contentDisplay;

    constructor() {
        super();
    }

    get buttonElement() {
        return this.querySelector(this.getAttribute("button-selector"));
    }

    get contentElement() {
        return this.querySelector("popover-content");
    }

    get placement() {
        return this.getAttribute("placement") || "top";
    }
    
    get middlewares() {
        var middlewares = [];
        if (this.getAttribute("flip") != null) {
            middlewares.push(window.FloatingUIDOM.flip());
        }
        if (this.getAttribute("shift") != null) {
            middlewares.push(window.FloatingUIDOM.shift());
        }
        return middlewares;
    }


    get openEvent() {
        return this.getAttribute("open-event") || "click";
    }

    get openDelay() {
        return Number(this.getAttribute("open-delay") || "100");
    }

    get closeEvents() {
        return new Set(this.getAttribute("close-event")?.split(' ') || ["click-other"]);
    }

    get mouseLeaveDelay() {
        return Number(this.getAttribute("mouse-leave-delay") || "100");
    }

    #mouseInContent = false;
    #mouseInButton = false;
    #popoverOpened = true;
    #cleanup = null;

    closePopover() {
        if (this.#popoverOpened) {
            this.contentElement.style.display = "none";
            this.#popoverOpened = false;
            this.dispatchEvent(new Event("popover-closed"));
        }
        if (this.#cleanup != null) {
            this.#cleanup();
        }
    }

    #shouldIgnoreClose(self) {
        if (!self.closeMouseOnContent && self.#mouseInContent) {
            return true;
        }
        if (!self.closeMouseOnButton && self.#mouseInButton) {
            return true;
        }
        return false;
    }

    connectedCallback() {
        const self = this;

        if (self.style.display == null || self.style.display == "") {
            self.style.display = "block";
        }

        const contentDisplayDef = self.getAttribute('content-display');
        if (contentDisplayDef != null && contentDisplayDef != "") {
            self.#contentDisplay = contentDisplayDef;
        } else {
            self.#contentDisplay = self.contentElement.style.display;
        }
        self.closePopover();

        self.buttonElement.addEventListener('mouseenter', event => {
            self.#mouseInButton = true;
        });
        self.buttonElement.addEventListener('mouseleave', event => {
            self.#mouseInButton = false;
        });
        self.contentElement.addEventListener('mouseenter', event => {
            self.#mouseInContent = true;
        });
        self.contentElement.addEventListener('mouseleave', event => {
            self.#mouseInContent = false;
        });


        self.buttonElement.addEventListener(self.openEvent, event => {
            if (event.type === self.openEvent) {
                setTimeout(() => {
                    // if mouse left the area after open delay, do not open popover
                    if (self.openEvent === "mouseenter" && !this.#mouseInButton) {
                        return;
                    }
                    self.contentElement.style.display = self.#contentDisplay;
                    self.contentElement.style.position = "absolute";
                    self.#popoverOpened = true;
                    self.dispatchEvent(new Event("popover-opened"));
                    self.#cleanup = window.FloatingUIDOM.autoUpdate(self.buttonElement, self.contentElement, () => {
                        window.FloatingUIDOM.computePosition(self.buttonElement, self.contentElement, {
                            placement: self.placement,
                            middleware: self.middlewares,
                        }).then(({x, y}) => {
                            self.contentElement.style.left = `${x}px`;
                            self.contentElement.style.top = `${y}px`;
                        });
                    });
                }, self.openDelay);
            }
        });

        if (self.closeEvents.has("click-other")) {
            document.addEventListener("click", event => {
                if (self.#mouseInButton || self.#mouseInContent) {
                    return;
                };
                self.closePopover();
            });
        }

        if (self.closeEvents.has("click-button")) {
            self.buttonElement.addEventListener("click", event => {
                self.closePopover();
            });
        }

        if (self.closeEvents.has("click-content")) {
            self.contentElement.addEventListener("click", event => {
                self.closePopover();
            });
        }

        if (self.closeEvents.has("mouseleave")) {

            const mouseLeaveHandler = (event) => {
                setTimeout(() => {
                    // mouse has entered the element again, do not close
                    if (self.#mouseInButton || self.#mouseInContent) {
                        return;
                    };
                    self.closePopover();
                }, self.mouseLeaveDelay);
            }

            self.buttonElement.addEventListener("mouseleave", mouseLeaveHandler);
            self.contentElement.addEventListener("mouseleave", mouseLeaveHandler);
        }

    }

    adoptedCallback() {
        this.closePopover();
    }

    disconnectedCallback() {
        this.closePopover();
    }

}

window.customElements.define("popover-menu", PopoverMenu);