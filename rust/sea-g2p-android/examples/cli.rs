// Shift-left check: run the vendored Rust core directly (no uniffi/Android
// needed) and compare its output against the real Python sea_g2p package on
// the same inputs, before spending time on cross-compilation.
use sea_g2p_android::SeaG2p;

fn main() {
    let mut args = std::env::args().skip(1);
    let dict_path = args.next().expect("usage: cli <dict_path> <text...>");
    let text = args.collect::<Vec<_>>().join(" ");
    let g2p = SeaG2p::new(dict_path, "vi".to_string()).expect("failed to load dict");
    println!("{}", g2p.run(text, true));
}
