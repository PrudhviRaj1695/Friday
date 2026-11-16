# FRIDAY Windows Local Worker — Phase 2

This service sends prompts only to Ollama running on this Windows PC. It does not call a cloud LLM provider.

## 1. Install Ollama

Install Ollama for Windows from https://ollama.com/download. In PowerShell:

```powershell
ollama pull qwen2.5:3b
ollama list
```

For a 16 GB RAM laptop, begin with the 3B model. Performance depends on GPU, memory, and other running applications.

## 2. Start the worker

Install Python 3.11 or newer. Open this folder in File Explorer and double-click `start-friday.bat`. Keep the terminal window open.

## 3. Find the Windows LAN IP

Run `ipconfig` in Command Prompt. Find the Wi-Fi adapter's IPv4 address (for example, 192.168.1.20). Allow Python through Windows Firewall on **Private networks only** if prompted.

## 4. Connect Android

Connect the phone and PC to the same trusted Wi-Fi. In FRIDAY, enter `http://YOUR-WINDOWS-IP:8765`, then tap **Test**.

## Development security warning

This first LAN integration uses HTTP without authenticated pairing. Use only on a trusted private Wi-Fi network. Do not use public Wi-Fi, router port forwarding, or sensitive data. The next milestone is authenticated pairing, TLS, and certificate pinning.
