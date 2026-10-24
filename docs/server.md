# Home server (`red8`)

Always-on old laptop that hosts the radar backend (Phase 4 of [plan.md](plan.md)) and, later, other personal services. Access: `ssh r2d8@red8` with a key, over Tailscale.

## Current state (cleaned up 2026-10-23)

- **Hardware/OS:** HP laptop, Intel i5-6200U (4 threads), 7.6 GB RAM, 238 GB SSD (LVM root 100 GB, about 135 GB unallocated), Ubuntu Server 24.04.5 LTS (supported to 2029).
- **Network:** Wi-Fi only, on the router's **guest** network `FRITZ! Guest` (intentional). Tailscale IP `100.95.20.125`, key expiry disabled. Ethernet port exists, no cable.
- **SSH (hardened 2026-10-24):** key login only. `/etc/ssh/sshd_config.d/10-no-password.conf` sets `PasswordAuthentication no`, `KbdInteractiveAuthentication no`, `PermitRootLogin no` (it sorts before Ubuntu's `50-cloud-init.conf`, so it wins). Checked: key login works, password login answers `Permission denied (publickey)`. To undo: delete that file (new connections read the config). Losing the laptop's SSH key now means going to the server physically.
- **Tailnet access rules (2026-10-24):** deny by default; the laptop (`100.86.74.124`) may reach everything, the phone (`100.125.5.80`) only `red8` on `tcp:8080`; tests in the policy refuse a save that breaks this; Funnel is off. Edited by the user in the admin console (Access controls, JSON editor), never by Claude. Checked from the phone: 8080 answers, port 22 and the laptop do not; the laptop still reaches SSH; the server cannot open connections to the phone. A new device gets no access until a rule (and a `hosts` entry) is added for it; a device that is removed and added again gets a new address, which must be updated in `hosts`. The full policy is in [plan.md](plan.md), Phase 7.
- **Users/sudo:** only `r2d8`. Sudo needs a password; no temporary rules remain. `r2d8` is in the `docker` group (root-equivalent).
- **Firewall (ufw):** active, default deny incoming, only port 22 allowed.
- **Docker:** Docker CE 29.x and compose plugin from Docker's apt repo, log rotation set (`local` driver, 10 MB x 3), compose projects go in `/srv/stacks/<service>/` (`compose.yaml`, data in `./data`).
- **Other:** security updates are automatic (unattended-upgrades, no automatic reboot), lid switch ignored, no snaps. The old Minecraft server, its Telegram bot, both Traccar installs and the users `minecraft`, `trekking`, `storage` were removed.

## Rules (the server is only reachable over Tailscale)

- Never change netplan, the Wi-Fi settings, Tailscale or the SSH setup remotely, and keep a second SSH session open for any risky change. Use a self-revert timer for firewall changes.
- Do **not** enable Tailscale SSH remotely: it takes over port 22 on the Tailscale address (the command itself warns it drops your session) and depends on the tailnet access rules.
- Reboot only when someone at home could check the laptop if it does not come back.
- Docker publishes ports around ufw: publish container ports on `127.0.0.1` or `100.95.20.125`, never on all interfaces.
- Claude has no general sudo. Privileged steps are scripts copied to `/tmp` on the server that the user runs with `sudo bash`, writing a readable `/tmp/*.out` result. Always ask before destructive steps.

## Pending

- **Reboot (done 2026-10-24):** kernel 6.8.0-147 now running, no reboot pending, Docker, Tailscale and the radar container came back by themselves (about a minute). `ssh.service` shows disabled because Ubuntu 24.04 starts SSH through `ssh.socket`, which is enabled; that is normal. A reboot over SSH works (`ssh -t r2d8@red8 'sudo reboot'`, needs the sudo password, so the user runs it); the only risk is the laptop not rejoining Wi-Fi/Tailscale, which needs physical access.
- **Radar backend 24 h check:** confirm `docker logs weather-radar` shows no errors and `/health` is `ok` a day after the reboot.
- Optional hardening (with revert timers): `ufw allow in on tailscale0`. (SSH password login: off since 2026-10-24.)
- Check/disable Wi-Fi power saving (`iw` is not installed).

## Later

- **Weather radar backend** (Phase 4): Compose stack in `/srv/stacks/weather-radar`, project in `~/Desktop/projects/weather-radar` on the PC (GitHub: R2-D8/weather_radar_backend). Runs as its own system user `weather-radar` (uid/gid 10001, no sudo, no docker group, no login shell); the stack directory belongs to `r2d8`, only `./data` belongs to the service user. Port 8080 published on `100.95.20.125` only. Setup script `deploy/create-user.sh` (run by the user with sudo), deploy with `deploy/deploy.sh`. Status: **deployed 2026-10-24**, reachable at `http://100.95.20.125:8080` over Tailscale from the PC and the phone, survived a reboot; the 24 h check is still to do. The service user's group id is 987 (not 10001) until the updated `create-user.sh` is re-run; harmless.
- **Pi-hole:** needs a fixed LAN IP and the laptop on the **main** network (the guest network isolates it), so an Ethernet cable or the main Wi-Fi. Port 53 conflicts with the `systemd-resolved` stub and must be freed first.
- **Password manager** (Vaultwarden) and **git server** (Forgejo, lighter than GitLab for this hardware). A password manager needs real backups before it is used.
- Backups: none needed today (nothing irreplaceable on the server); decide a target when the first irreplaceable data arrives.
