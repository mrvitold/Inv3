const STORAGE_KEY = 'inv3_lang';

function getStoredLang() {
    return localStorage.getItem(STORAGE_KEY) || 'en';
}

function setStoredLang(lang) {
    localStorage.setItem(STORAGE_KEY, lang);
}

function getTranslations(lang) {
    return translations[lang] || translations.en;
}

function applyTranslations(lang) {
    const t = getTranslations(lang);

    document.querySelectorAll('[data-i18n]').forEach(el => {
        const key = el.getAttribute('data-i18n');
        if (t[key]) {
            el.textContent = t[key];
        }
    });

    document.querySelectorAll('[data-i18n-attr]').forEach(el => {
        const attr = el.getAttribute('data-i18n-attr');
        const key = el.getAttribute('data-i18n-attr-key');
        if (attr && key && t[key]) {
            el.setAttribute(attr, t[key]);
        }
    });

    document.documentElement.lang = lang === 'lt' ? 'lt' : 'en';

    document.querySelectorAll('.lang-btn').forEach(btn => {
        btn.classList.toggle('active', btn.getAttribute('data-lang') === lang);
    });
}

function initLanguageSwitcher() {
    const stored = getStoredLang();
    applyTranslations(stored);

    document.querySelectorAll('.lang-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const lang = btn.getAttribute('data-lang');
            setStoredLang(lang);
            applyTranslations(lang);
        });
    });
}

document.addEventListener('DOMContentLoaded', initLanguageSwitcher);
