use std::path::Path;

fn main() {
    println!("cargo:rerun-if-changed=../app/src/main/res/drawable/ic_podvault.png");
    println!("cargo:rerun-if-changed=build.rs");

    let png_path = Path::new("../app/src/main/res/drawable/ic_podvault.png");
    let out_dir = std::env::var("OUT_DIR").unwrap();
    let ico_path = Path::new(&out_dir).join("podvault.ico");

    if png_path.exists() {
        if let Ok(img) = image::open(png_path) {
            let resized = img.resize_exact(256, 256, image::imageops::FilterType::Lanczos3);
            let _ = resized.save_with_format(&ico_path, image::ImageFormat::Ico);

            #[cfg(windows)]
            {
                let mut res = winres::WindowsResource::new();
                res.set_icon(ico_path.to_str().unwrap());
                res.set("ProductName", "PodVault");
                res.set("FileDescription", "PodVault — Gestionnaire de podcasts 100 % local");
                res.set("CompanyName", "Les-Doubles-A");
                let _ = res.compile();
            }
        }
    }
}
