use std::env;
use std::path::Path;
use std::process;

fn ensure_initialized(repo: &str, password: &str) {
    let config = Path::new(repo).join("config");
    if !config.exists() {
        eprintln!("Auto-initializing repository at {}", repo);
        if let Err(e) = rustic::init_repository(repo, password) {
            eprintln!("Failed to init repository: {}", e);
            process::exit(2);
        }
        let gitignore = Path::new(repo).join(".rusticignore");
        if !gitignore.exists() {
            if let Err(e) = std::fs::write(&gitignore, "# rusticignore\n") {
                eprintln!("Warning: could not create .rusticignore: {}", e);
            }
        }
    }
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() < 3 {
        eprintln!("Usage: rustic <cmd> <repo> [password] [args...]");
        eprintln!("  cmds: init, backup, restore, check");
        process::exit(1);
    }
    let cmd = &args[1];
    let repo = &args[2];
    let password = if args.len() >= 4 { args[3].as_str() } else { "x" };
    let has_explicit_password = args.len() >= 4;

    let result = match cmd.as_str() {
        "init" => rustic::init_repository(repo, password),
        "backup" => {
            let tag_offset = if has_explicit_password { 4 } else { 3 };
            if args.len() < tag_offset + 1 {
                eprintln!("backup: <repo> [password] <tag> <source...>");
                process::exit(1);
            }
            ensure_initialized(repo, password);
            let tag = &args[tag_offset];
            let sources: Vec<String> = args[tag_offset + 1..].to_vec();
            rustic::create_snapshot(repo, password, &sources, &[tag.clone()])
                .map(|id| {
                    println!("{}", id);
                })
        }
        "restore" => {
            let tag_offset = if has_explicit_password { 4 } else { 3 };
            if args.len() < tag_offset + 1 {
                eprintln!("restore: <repo> [password] <snapshot_id> <target>");
                process::exit(1);
            }
            ensure_initialized(repo, password);
            let snapshot_id = &args[tag_offset];
            let target = &args[tag_offset + 1];
            rustic::restore_snapshot(repo, password, snapshot_id, target)
        }
        "check" => {
            ensure_initialized(repo, password);
            rustic::check_repository(repo, password)
        }
        _ => {
            eprintln!("Unknown command: {}", cmd);
            process::exit(1);
        }
    };

    if let Err(e) = result {
        eprintln!("Error: {}", e);
        process::exit(2);
    }
}
