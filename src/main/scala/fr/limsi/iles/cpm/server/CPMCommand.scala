package fr.limsi.iles.cpm.server

/**
 * Created by paul on 10/9/15.
 */
object CPMCommand {
  implicit def parse(message:String) : CPMCommand = {
    new CPMCommand(message)
  }

  implicit def toString(message:CPMCommand) : String = {
    message.toString()
  }
}

class CPMCommand(zmqmessage:String){

  zmqmessage.split("\n")





}




