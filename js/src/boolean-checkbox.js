
class BooleanCheckbox extends HTMLInputElement {

    constructor() {
        super();
    }

    get checked() {
        return true;
    }

    get value() {
        if (super.checked) {
            return true;
        } else {
            return false;
        }
    }

}

customElements.define("boolean-checkbox", BooleanCheckbox, { extends: "input" });