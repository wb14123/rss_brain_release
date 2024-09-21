
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
window.setTheme = setTheme;

function getCurrentTheme() {
    const theme = localStorage.getItem("data-theme");
    if (theme) {
        return theme;
    }
    return "auto";
}
window.getCurrentTheme = getCurrentTheme;

function initTheme() {
    setTheme(getCurrentTheme());
}

initTheme();
