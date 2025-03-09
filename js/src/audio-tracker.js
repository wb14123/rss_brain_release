class AudioTracker extends HTMLAudioElement {
  constructor() {
    super();
    this.sendProgress = this.sendProgress.bind(this);
    this.handleVisibilityChange = this.handleVisibilityChange.bind(this);
  }

  connectedCallback() {
    // Add event listeners
    this.addEventListener('pause', this.sendProgress);
    window.addEventListener('beforeunload', this.sendProgress);
    window.addEventListener('pagehide', this.sendProgress);
    document.addEventListener('visibilitychange', this.handleVisibilityChange);

    if (this.hasAttribute('saved-progress')) {
      const time = parseFloat(this.getAttribute('saved-progress'));
      if (isNaN(time)) return;
  
      const setTime = () => {
        // saved-progress is in ms instead of seconds
        this.currentTime = time / 1000;
        this.removeEventListener('loadedmetadata', setTime);
      };
  
      if (this.readyState >= HTMLMediaElement.HAVE_METADATA) {
        setTime();
      } else {
        this.addEventListener('loadedmetadata', setTime);
      }
    }

  }

  disconnectedCallback() {
    // Clean up event listeners
    this.removeEventListener('pause', this.sendProgress);
    window.removeEventListener('beforeunload', this.sendProgress);
    window.removeEventListener('pagehide', this.sendProgress);
    document.removeEventListener('visibilitychange', this.handleVisibilityChange);
  }

  handleVisibilityChange() {
    if (document.visibilityState === 'hidden') {
      this.sendProgress();
    }
  }

  sendProgress() {
    const dataUrl = this.getAttribute('save-progress-url');
    // Use sendBeacon for reliable delivery during page unload
    const curTimeMs = Math.floor(this.currentTime * 1000);
    navigator.sendBeacon(dataUrl + curTimeMs.toString());
  }
}

// Register the custom element
window.customElements.define('audio-tracker', AudioTracker, { extends: 'audio' });