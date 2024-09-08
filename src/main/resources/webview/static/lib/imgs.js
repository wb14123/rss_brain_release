"use strict";
class Images extends HTMLImageElement {
    constructor() {
        super();
        this.srcs = [];
        this.srcIdx = 0;
        this.cacheResult = false;
        this.useLock = false;
    }
    connectedCallback() {
        var _a, _b;
        this.srcs = (_b = (_a = this.getAttribute("srcs")) === null || _a === void 0 ? void 0 : _a.split(" ")) !== null && _b !== void 0 ? _b : [];
        if (this.srcs.length == 0) {
            throw new Error("srcs not defined for imgs tag");
        }
        this.cacheResult = this.getAttribute("cache-result") !== null;
        this.useLock = this.getAttribute("use-lock") !== null;
        this.loadImg();
    }
    getLockKey(url) {
        return "imgs-lock-" + url;
    }
    getStorageKey(url) {
        return "imgs-result-" + url;
    }
    shouldLoadImage(url) {
        var _a;
        return !this.cacheResult || ((_a = window.localStorage.getItem(this.getStorageKey(url))) !== null && _a !== void 0 ? _a : "true") == "true";
    }
    setImageResult(url, result) {
        if (!this.cacheResult) {
            return;
        }
        window.localStorage.setItem(this.getStorageKey(url), String(result));
    }
    loadImg() {
        if (this.srcIdx >= this.srcs.length) {
            console.log("All srcs has used");
            return;
        }
        let resolve;
        let reject;
        const promise = new Promise((res, rej) => {
            resolve = res;
            reject = rej;
        });
        const url = this.srcs[this.srcIdx];
        if (!this.shouldLoadImage(url)) {
            this.srcIdx++;
            return this.loadImg();
        }
        if (this.useLock) {
            navigator.locks.request(this.getLockKey(url), (lock) => promise);
        }
        this.onload = (event) => {
            this.setImageResult(url, true);
            resolve(true);
        };
        this.onerror = (event) => {
            this.setImageResult(url, false);
            resolve(false);
            this.srcIdx++;
            this.loadImg();
        };
        this.setAttribute("src", url);
    }
}
Images.observedAttributes = ["srcs", "cache-result", "use-lock"];
customElements.define("multi-imgs", Images, { extends: "img" });
