
function setTheme(name) {
    var realName = name;
    if (name == "auto") {
        if (window.matchMedia('(prefers-color-scheme: dark)').matches) {
            realName = "dark";
        } else {
            realName = "light";
        }
    }
    document.querySelector('body').setAttribute('data-theme', realName);
    localStorage.setItem("data-theme", name);
}

function getCurrentTheme() {
    const theme = localStorage.getItem("data-theme");
    if (theme) {
        return theme;
    }
    return "auto";
}

function initTheme() {
    setTheme(getCurrentTheme());
}

initTheme();