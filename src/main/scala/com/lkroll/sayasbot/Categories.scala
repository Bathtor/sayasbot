package com.lkroll.sayasbot

import net.katsstuff.ackcord.commands.CmdCategory

object Categories {
  val generalCommands = "/";
  val adminCommands = "!";
  val pipeCommand = "|";

  def all = Set(generalCommands, adminCommands, pipeCommand);
}
