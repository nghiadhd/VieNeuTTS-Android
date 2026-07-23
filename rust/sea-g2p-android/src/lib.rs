//! Android bindings for sea-g2p's Rust core.
//!
//! `g2p`, `punc`, and `vi_normalizer` below are vendored, byte-for-byte-logic
//! copies of the pure-Rust modules from github.com/pnnbao97/sea-g2p
//! (Apache-2.0) — none of those modules used pyo3 internally (only the thin
//! wrapper in the upstream `lib.rs` did), so no G2P/normalization logic
//! changed in the port. This file replaces that pyo3 wrapper with a uniffi
//! one so the same core runs from Kotlin instead of Python.
pub mod g2p;
pub mod punc;
pub mod vi_normalizer;

use std::sync::Arc;

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SeaG2pError {
    #[error("failed to open dictionary: {0}")]
    DictLoad(String),
}

/// Mirrors `sea_g2p.SEAPipeline`: normalize (Vietnamese text normalization)
/// then phonemize (dictionary-backed G2P), against a single loaded dictionary.
#[derive(uniffi::Object)]
pub struct SeaG2p {
    normalizer: vi_normalizer::Normalizer,
    engine: g2p::G2PEngine,
}

#[uniffi::export]
impl SeaG2p {
    /// `dict_path` is the on-device path to `sea_g2p.bin` (copied out of
    /// Android assets at first run, since assets aren't directly mmap-able
    /// by path). `lang` is passed straight through to the normalizer, same
    /// as `sea_g2p.SEAPipeline(lang=...)` — VieNeu-TTS always uses `"vi"`.
    #[uniffi::constructor]
    pub fn new(dict_path: String, lang: String) -> Result<Arc<Self>, SeaG2pError> {
        let engine = g2p::G2PEngine::new(&dict_path)
            .map_err(|e| SeaG2pError::DictLoad(e.to_string()))?;
        let normalizer = vi_normalizer::Normalizer::new(&lang);
        Ok(Arc::new(Self { normalizer, engine }))
    }

    /// Mirrors `SEAPipeline.run(text, punc_norm)`: normalize(text, punc_norm)
    /// then phonemize the normalized text (phonemize itself always runs with
    /// punc_norm=false, matching `pipeline.py`'s `g2p.convert(normalized_text)`
    /// call, which does not forward punc_norm).
    pub fn run(&self, text: String, punc_norm: bool) -> String {
        if text.is_empty() {
            return String::new();
        }
        let normalized = self.normalizer.normalize(&text, punc_norm);
        self.engine.phonemize(&normalized)
    }

    /// Mirrors `punc_norm()` as a standalone pure-string function — used by
    /// VieNeu-TTS to re-terminate chunk/phoneme boundaries without re-running
    /// normalize/G2P (see `phonemize_text.py`'s `phonemize_text_with_emotions`).
    pub fn punc_norm_only(&self, text: String) -> String {
        punc::apply_punc_norm(&text)
    }
}
