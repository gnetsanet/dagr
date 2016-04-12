/*
 * The MIT License
 *
 * Copyright (c) 2015 Fulcrum Genomics LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package dagr.core.tasksystem

import dagr.core.execsystem.{Cores, Memory}

/** Companion object to provide a couple of helper constructors for ShellCommand. */
object ShellCommand {
  /** Constructs a Shell Command using varargs. */
  def apply(args: Any*) : ShellCommand = new ShellCommand(args.map(_.toString):_*)

}

/**
  * Executes a command, with a set of arguments, in a shell.
  */
class ShellCommand(val commands: String*) extends ProcessTask with FixedResources {

  requires(Cores(1), Memory("32M"))
  if (commands.isEmpty) throw new IllegalArgumentException("No commands to ShellCommand")

  override def args: Seq[Any] = commands

}

/**
  * Executes a command, with a set of arguments, in a shell.  Arguments with special characters
  * or otherwise will be modified (quoted, escaped, etc.).  This may be dangerous depending on
  * the commands given.  In general, use `ShellCommand` if you are not 100% sure.
  */
class ShellCommandAsIs(commands: String*) extends ShellCommand(commands:_*) {
  this.quoteIfNecessary = false
}
