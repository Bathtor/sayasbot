SayAs Bot for Discord
=====================
A simple Discord bot in Scala using [Akka](https://akka.io/) and [AckCord](http://ackcord.katsstuff.net/).

Allows an admin user to register a number of *Characters* (with optional profile link and avatar), and then anyone can use `/sayas` to format a message as if it was said by the character.

Usage
-----
Use `/help` from within a channel that has the **SayAsBot** as a member to see available commands.

Installation
------------
In order to install the SayAsBot, you need both a server with the ability to run Java archives (`.jar`-files) and on or more Discord server(s) you want to add the bot to.

1. Create a new [Discord application](https://discordapp.com/developers/applications/).
1. Create a bot within the application.
1. Add the bot to your Discord server(s) by replacing `CLIENTID` with the app's *CLIENT ID* (from *General Information*) in `https://discordapp.com/oauth2/authorize?client_id=CLIENTID&scope=bot`.
1. At the confirmation page copy the bot's *Token* (if you like pictures, look [here](https://github.com/jagrosh/MusicBot/wiki/Getting-a-Bot-Token)).
1. Create a file `application.conf` on your server with the content as below (replacing text in `< >` with the appropriate data).
1. Copy `SayAsBot-assembly-1.0.0.jar` from the release to the same folder as `application.conf`.
1. Run `java -Dconfig.file=./application.conf -jar SayAsBot-assembly-1.0.0.jar` from that folder.
1. *Optional*: You can use a `start.sh` script like below to have the bot running in the background and log output to `output.log`.

##### application.conf
```HOCON
sayasbot {
	admin-id = <YOUR USER ID>
	token = "<BOT TOKEN>"
}

akka {
  loglevel = "INFO"
  log-config-on-start = off

  extensions = [akka.persistence.Persistence]

  persistence {

    journal {
      plugin = "akka.persistence.journal.leveldb"
      auto-start-journals = ["akka.persistence.journal.leveldb"]
      #leveldb.dir = "target/journal"
    }

    snapshot-store {
      plugin = "akka.persistence.snapshot-store.local"
      auto-start-snapshot-stores = ["akka.persistence.snapshot-store.local"]
      #local.dir = "target/snapshots"
    }

  }
}
```

##### start.sh
```bash
#!/bin/bash
nohup java -Dconfig.file=./application.conf -jar SayAsBot-assembly-1.0.0.jar &> output.log &
```

License
-------
Licensed under the terms of MIT license.

See [LICENSE](LICENSE) for details.