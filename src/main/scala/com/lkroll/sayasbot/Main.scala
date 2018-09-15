package com.lkroll.sayasbot

import scala.language.higherKinds

import net.katsstuff.ackcord._
import net.katsstuff.ackcord.data._
import net.katsstuff.ackcord.commands._
import net.katsstuff.ackcord.syntax._
import net.katsstuff.ackcord.util._
import cats.{ Monad, Id }
import scala.concurrent.Await
import scala.concurrent.duration._
import com.typesafe.scalalogging.StrictLogging
import com.typesafe.config.ConfigFactory
import akka.actor.{ ActorSystem, ActorRef }
import akka.stream.scaladsl.Keep
import akka.NotUsed

object Main extends StrictLogging {

  val conf = ConfigFactory.load();

  val token = conf.getString("sayasbot.token");
  val adminId = conf.getLong("sayasbot.admin-id");

  val maxRestartNum = 2;
  val maxRestartInterval = 30.seconds;

  val stopped = new java.util.concurrent.atomic.AtomicBoolean(false);

  def main(args: Array[String]): Unit = {
    var system: ActorSystem = null;
    var numRestarts = 0;
    var nextClean = maxRestartInterval fromNow;
    while (numRestarts < maxRestartNum) {
      try {
        system = ActorSystem("AckCord");
        run(system);
      } catch {
        case e: Throwable => {
          logger.error("Bot is restarting due an error.", e);
          val tf = system.terminate();
          Await.ready(tf, 30.seconds);
          numRestarts += 1;
          if (nextClean.isOverdue()) {
            numRestarts = 0;
            nextClean = maxRestartInterval fromNow;
          }
        }
      }
    }
    logger.error(s"Restart limit $maxRestartNum within $maxRestartInterval reached. Shutting down.");
    println("Restart limit reached. Shutting down. Please consult the logs.");
    System.exit(1);
  }

  private def run(system: ActorSystem): Unit = {
    val clientSettings = ClientSettings(
      token = token,
      commandSettings = CommandSettings(needsMention = false, prefixes = Categories.all),
      system = system);
    import clientSettings.executionContext;
    println(s"Building client with $clientSettings");
    val futureClient = clientSettings.build();
    println("Awaiting client...");
    val client = Await.result(futureClient, 30.seconds);
    println(s"Got client: $client");
    val helpCmdActor: ActorRef = system.actorOf(HelpCommand.props(client.requests), "HelpCmd");
    val helpCommand = HelpCommandFactory[Id](helpCmdActor);
    def register[Mat](commandFactory: ParsedCmdFactory[Id, _, Mat]): Mat = {
      registerCmd(client.commands, helpCmdActor)(commandFactory)
    }
    register(helpCommand);
    val commands = new BotCommands[Id]();
    val sayAsCmdActor: ActorRef = system.actorOf(SayAsCmd.props(client.requests), "SayAsCmd");
    commands.registerAll(sayAsCmdActor, register);

    client.onEvent[Id] {
      case APIMessage.Ready(_) => println("Now ready")
      case _                   => client.sourceRequesterRunner.unit
      //case m: APIMessage.MessageMessage => println(s"Got ${m.message}")
      //case x                            => println(s"Got unexpected message: $x")
    }
    //import RequestDSL._
    //    client.onRawCommandDSLC { implicit c =>
    //      {
    //        case RawCmd(message, generalCommands, "echo", args, _) =>
    //          for {
    //            channel <- optionPure(message.tGuildChannel[Id].value)
    //            _ <- channel.sendMessage(s"ECHO: ${args.mkString(" ")}")
    //          } yield ()
    //      }
    //    }

    client.login();

    val terminatedF = system.whenTerminated;
    while (!terminatedF.isCompleted) {
      try {
        Await.ready(terminatedF, 1.second);
        if (stopped.get) {
          logger.info("All stopped.");
          System.exit(0);
        } else {
          logger.warn("The actor system stopped without being asked to. Trying to restart...");
        }
      } catch {
        case _: java.util.concurrent.TimeoutException => if (stopped.get) {
          logger.info("Got asked to stop. Shutting down...");
          val logoutF = client.logout(5.seconds);
          try {
            val loggedOut = Await.result(logoutF, 10.seconds);
            if (loggedOut) {
              logger.info("Client logged out successfully.");
            } else {
              logger.warn("Client logout did not succeed. Terminating anyway.");
            }
          } catch {
            case _: java.util.concurrent.TimeoutException => logger.error("Logout didn't occur in time.")
            case e: Throwable                             => logger.error("There was an error during logout.", e)
          } finally {
            system.terminate();
          }
        }
      }
    }
  }

  def registerCmd[Mat](commands: Commands[Id], helpCmdActor: ActorRef)(commandFactory: ParsedCmdFactory[Id, _, Mat]): Mat = {
    val (complete, materialised) = commands.subscribe(commandFactory)(Keep.both);
    (commandFactory.refiner, commandFactory.description) match {
      case (info: AbstractCmdInfo[Id], Some(description)) => helpCmdActor ! HelpCmd.AddCmd(info, description, complete)
      case _ => ()
    }
    materialised
  }
}
