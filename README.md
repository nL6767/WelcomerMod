# WelcomerMod

**Version:** 1.0.0
**Author:** sq3rrr (nL66ercatgirl67 while testing)
**GitHub:** [WelcomerMod](https://github.com/sq3rrr/WelcomerMod)

---

## Overview

WelcomerMod is a customizable chat-enhancer for Minecraft Anarchy servers, specifically designed for **Constantiam**. It automatically greets players on join and allows the client user to self-greet.

Will work on any Vanilla server that hasn t modified join messages.
Does not work; On DONUTsmp, Oldfag, 2b, basically all gayass servers and I wont change that. Clone repo and change this line for your Server if it uses different join-notifications:

for regex matcher:

private final Pattern joinPattern = Pattern.compile("^(.*?) joined the game$"); // <--------- change this line 


---

## Features

* Automatic welcome-messages onJoin for other players.
* Self-greet functionality for user.
* Config for WelcomeMessages and SelfGreet`.
* Possibility to ignore players who are annoyed (unlike meteor or mio)
* Different server modes (Default mode 5min cd per player/ Const. mode 5 min general cooldown / strict mode. 10 min gen.cd)
* Lightweight mod (removed all statistics tracking or memory trackers.)

---

## Installation

1. Ensure you have **Fabric API** installed and inside mods /.minecraft/mods/fabric_api_whaterverrelease.
2. Copy `WelcomerMod.jar` into the `mods` folder of your Minecraft instance.

OPTIONAL (but recommended if you want to hardcode stuff in yourself)
If you have big big trust issues (like I do) build the jar yourself

1.) scan code with gpt/maliciousintentscanner
2.) clone repo
3.) build jar 


---

## Configuration

* **Messages:** Located in `resources/messages.txt`.
* **Privacy:** The mod does not track IP addresses, external statistics, or send data outside of Minecraft.
* **Reminder:** Base config for welcomemessages is pretty dry; adjust them with your own messages/jokes etc.
*  The mod will build a basic configs first, which should be costumized by YOU.

---

## Commands

- /welcomer toggle
- /welcomer status
- /welcomer selfgreat toggle
- /welcomer config add
- /welcomer config add addself
- /welcomer config reload
- /welcomer config reloadself
- /welcomer mode
- /welcomer info
  

---

## Development

* **Build System:** Gradle
* **IDE Support:** IntelliJ IDEA, Eclipse, VSCode
* **Java Version:** Compatible with Java 21+


## Credits & Shoutouts

* **Main Testserver:**
Constantiam.net, anarchy.ac (BANNED;TheBanhammerHasSpoken), Vanilla+(BANNED #inappropriateusername), Qndres.net(#BANid2016|BANNED;permanent,reason;bot)
* **Tools & Assistance:** OpenAI ChatGPT for bugfixes/codeblock generation

---

## Privacy & Safety

* No IPs or sensitive data is tracked.
* All configuration data (messages, custom settings) is stored locally.
* Make sure to **keep `messages.txt` and other configs safe** if containing personal notes.

---

## License

Fuck licences nga basic github aah licence

---

Have fun!
