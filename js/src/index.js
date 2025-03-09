
// css

import '../css/pico.jade.min.css';
import 'somment/somment.css';
import 'lite-youtube-embed/src/lite-yt-embed.css';
import 'toastify-js/src/toastify.css';
import 'viewerjs/dist/viewer.min.css';
import '../css/google-fonts.css';
import '../css/main.css';

// js
import '@ungap/custom-elements';
import './boolean-checkbox.js';

import 'htmx.org';
import './global-htmx.js';
import 'htmx.org/dist/ext/json-enc.js';

import Alpine from 'alpinejs';
window.Alpine = Alpine;

import * as FloatingUIDOM from '@floating-ui/dom';
window.FloatingUIDOM = FloatingUIDOM;

import 'lite-youtube-embed';
import Toastify from 'toastify-js';
window.Toastify = Toastify;

import DOMPurify from 'dompurify';
window.DOMPurify = DOMPurify;

import 'imgs-html';
import 'somment';

import './viewer_component.js';

import './error-handler.js';
import './popover-menu.js';
import './match-id.js';
import './set-theme.js';
import './source-images.js';
import './audio-tracker.js'
import './lite-youtube-tracker.js'

Alpine.start();