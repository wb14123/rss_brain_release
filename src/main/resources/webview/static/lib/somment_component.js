
import { Somment } from "./somment.js";


class SommentElement extends HTMLElement {

    static observedAttributes = ["link"];

    constructor() {
        super();
    }

    connectedCallback() {
        /*
        const shadow = this.attachShadow({ mode: "open" });

        const style = document.createElement("link");
        style.setAttribute("rel", "stylesheet");
        style.setAttribute("href", "somment.css");
        shadow.appendChild(style);
        */

        const elem = document.createElement("div");
        elem.setAttribute("id", "somment-root")

        // shadow.appendChild(elem);
        this.appendChild(elem);

        const somment = new Somment(this.getAttribute("link"), elem);
        somment.create();
    }
}

customElements.define("somment-comment", SommentElement);