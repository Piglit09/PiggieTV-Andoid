(() => {
    const brandName = 'PiggieTV';
    const brandLogo = '/native/ptv-sidebar-logo.png';
    const brandIcon = '/native/ptv-icon.png';
    const css = `
        :root {
            --ptv-focus: #7dd6ff;
            --ptv-focus-soft: #9fe7ff;
            --ptv-violet: #a78bfa;
            --ptv-violet-soft: #c9b5ff;
            --ptv-violet-deep: #2c1746;
            --ptv-accent: #ff8dc3;
            --ptv-night: #05050c;
            --ptv-panel: #140b24;
            --ptv-panel-high: #2c1746;
            --ptv-text: #f8f6ff;
            --ptv-muted: #c5bed8;
            --theme-primary-color: #7dd6ff;
            --theme-accent-color: #ff8dc3;
        }

        html,
        body,
        .backgroundContainer,
        .page,
        .libraryPage {
            background: linear-gradient(180deg, rgba(44, 23, 70, .88) 0, var(--ptv-panel) 34rem, var(--ptv-night) 100%) !important;
            color: var(--ptv-text) !important;
        }

        .skinHeader,
        .mainDrawer,
        .dashboardDocument .skinHeader {
            background: linear-gradient(90deg, rgba(5, 5, 12, .98), rgba(44, 23, 70, .94)) !important;
            border-color: rgba(201, 181, 255, .28) !important;
            color: var(--ptv-text) !important;
        }

        .paperList,
        .dialog,
        .actionSheet,
        .formDialogHeader,
        .formDialogFooter,
        .cardBox,
        .metadataEditorPaper {
            background-color: rgba(20, 11, 36, .94) !important;
            border-color: rgba(201, 181, 255, .22) !important;
        }

        .emby-button.raised,
        .button-submit,
        .button-accent,
        .fab,
        .raised.homeLibraryButton {
            background: linear-gradient(90deg, var(--ptv-focus), var(--ptv-violet), var(--ptv-accent)) !important;
            color: var(--ptv-night) !important;
            border: 0 !important;
        }

        .emby-button:not(.raised),
        .paper-icon-button-light,
        .navMenuOption,
        .listItem {
            color: var(--ptv-text) !important;
        }

        .navMenuOption-selected,
        .listItem:hover,
        .emby-tab-button-active {
            background-color: rgba(125, 214, 255, .16) !important;
            color: var(--ptv-focus) !important;
        }

        .emby-input,
        .emby-textarea,
        .emby-select-withcolor {
            background-color: rgba(5, 5, 12, .68) !important;
            border-color: rgba(125, 214, 255, .42) !important;
            color: var(--ptv-text) !important;
        }

        .sectionTitle,
        .pageTitle,
        .detailPagePrimaryContent,
        .mediaInfoItem,
        .secondaryText {
            color: var(--ptv-text) !important;
        }

        .secondaryText,
        .fieldDescription,
        .inputLabel {
            color: var(--ptv-muted) !important;
        }

        .mdl-slider-background-lower,
        .itemProgressBarForeground,
        .taskProgressInner {
            background-color: var(--ptv-focus) !important;
        }

        .ptv-native-brand {
            align-items: center;
            color: var(--ptv-text) !important;
            display: inline-flex;
            flex: 0 0 auto;
            margin-inline-end: .5rem;
            min-width: 0;
            text-decoration: none;
        }

        .ptv-native-brand img {
            display: block;
            height: 2.45rem;
            max-width: min(12rem, 48vw);
            object-fit: contain;
            width: auto;
        }

        .headerLogo,
        .pageTitleWithLogo img,
        img[src*="jellyfin"],
        img[alt="Jellyfin"] {
            filter: saturate(1.25) hue-rotate(126deg) !important;
        }
    `;

    function installStyles() {
        if (document.getElementById('ptv-native-brand-style')) return;

        const styleElement = document.createElement('style');
        styleElement.id = 'ptv-native-brand-style';
        styleElement.textContent = css;
        document.head.appendChild(styleElement);
    }

    function installIcon() {
        const existingIcon = document.querySelector('link[rel="icon"]')
            || document.querySelector('link[rel="shortcut icon"]')
            || document.createElement('link');
        existingIcon.rel = 'icon';
        existingIcon.type = 'image/png';
        existingIcon.href = brandIcon;
        if (!existingIcon.parentElement) document.head.appendChild(existingIcon);
    }

    function replaceBrandText(value) {
        return value && value.replace(/\bJellyfin\b/g, brandName);
    }

    function replaceBrandAttributes(root = document) {
        for (const element of root.querySelectorAll('[aria-label], [title], [alt]')) {
            for (const attribute of ['aria-label', 'title', 'alt']) {
                const value = element.getAttribute(attribute);
                const replacement = replaceBrandText(value);
                if (replacement && replacement !== value) element.setAttribute(attribute, replacement);
            }
        }
    }

    function installHeaderBrand() {
        const headerTarget = document.querySelector('.skinHeader .headerLeft')
            || document.querySelector('.skinHeader')
            || document.querySelector('.mainDrawer-scrollContainer');
        if (!headerTarget) return;

        const existingBrand = document.querySelector('.ptv-native-brand');
        if (existingBrand) {
            if (existingBrand.parentElement !== headerTarget) headerTarget.prepend(existingBrand);
            return;
        }

        const brand = document.createElement('a');
        brand.className = 'ptv-native-brand';
        brand.href = '#/home.html';
        brand.innerHTML = `<img src="${brandLogo}" alt="${brandName}">`;
        headerTarget.prepend(brand);
    }

    function applyBranding() {
        document.title = replaceBrandText(document.title) || brandName;
        installIcon();
        replaceBrandAttributes();
        installHeaderBrand();
    }

    installStyles();
    applyBranding();
    document.addEventListener('DOMContentLoaded', applyBranding);

    let queued = false;
    function scheduleBranding() {
        if (queued) return;
        queued = true;
        window.requestAnimationFrame(() => {
            queued = false;
            applyBranding();
        });
    }

    const observer = new MutationObserver(() => {
        scheduleBranding();
    });
    observer.observe(document.documentElement, {
        childList: true,
        subtree: true
    });
})();
