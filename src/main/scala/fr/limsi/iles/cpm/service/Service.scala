package fr.limsi.iles.cpm.service


import java.util.concurrent.Executors

import com.typesafe.scalalogging.LazyLogging
import fr.limsi.iles.cpm.module.parameter.AbstractModuleParameter
import fr.limsi.iles.cpm.process.{CMDProcess, DockerManager, RunEnv}
import fr.limsi.iles.cpm.module.value._
import fr.limsi.iles.cpm.process.DockerManager._
import fr.limsi.iles.cpm.server.{EventManager, EventMessage}
import fr.limsi.iles.cpm.utils.{ConfManager, YamlElt, YamlMap}
import org.json.JSONObject

import scala.sys.process._

/**
  * Created by paul on 3/30/16.
  */
class Service(val definitionPath:String,
              val name:String,
              val desc:String,
              val outputs:Map[String,AbstractModuleParameter],
              var startcmd:CMDVal,
              var stopcmd:Option[CMDVal]
             ) extends LazyLogging{

  var log : Option[String] = None
  var test : Option[String] = None

  private var _isRunning : Boolean = false
  private var runningContainer : Option[String] = None
  private var _lock = new Object()

  def getDefDir = {
    (new java.io.File(definitionPath)).getParent
  }

  def initEnv : RunEnv = {
    val env = new RunEnv(Map[String,AbstractParameterVal]())
    val resdir = DIR(None,None)
    resdir.fromYaml(ConfManager.get("result_dir").toString)
    env.setVar("_RESULT_DIR",resdir)
    val corpusdir = LIST[DIR](None,None)
    corpusdir.fromYaml(ConfManager.get("corpus_dir").asInstanceOf[java.util.ArrayList[String]])
    env.setVar("_CORPUS_DIR",corpusdir)
    val defdir = DIR(None,None)
    defdir.fromYaml(getDefDir)
    env.setVar("_DEF_DIR",defdir)
    val maxthreads = VAL(None,None)
    maxthreads.fromYaml(ConfManager.get("maxproc").toString)
    env.setVar("_MAX_THREADS",maxthreads)
    val resourcesdir = VAL(None,None)
    val resourcesdirval = ConfManager.get("resource_dir").toString+"/"+this.name
    if(!(new java.io.File(resourcesdirval)).exists()){
      (new java.io.File(resourcesdirval)).mkdirs();
    }
    resourcesdir.fromYaml(resourcesdirval);
    env.setVar("_RESOURCE_DIR",resourcesdir)
    env
  }

  def start():Boolean={
    _lock.synchronized{
      val env = initEnv
      if(startcmd.needsDocker()){
        val defdir = getDefDir
        val imagename = startcmd.inputs("DOCKERFILE").toYaml() match {
          case x :String => {
            if(x!="false"){
              // replace @ by "_at_" (docker doesn't accept @ char)
              val dockerfile = new java.io.File(defdir+"/"+x)
              val dockerfilename = if (dockerfile.exists()){
                dockerfile.getName
              }else{
                "Dockerfile"
              }
              val name = DockerManager.nameToDockerName("service-"+this.name+"-"+dockerfilename) // _MOD_CONTEXT should always be the module defintion that holds this command
              if(!DockerManager.exist(name)){
                DockerManager.build(name,defdir+"/"+dockerfilename)
              }
              name
            }else{
              ""
            }
          }
          case _ =>  ""
        }
        val mount = "-v " + defdir + ":" + defdir
        val dockercmd = "docker run "+ env.resolveValueToString(startcmd.inputs("DOCKER_OPTS").asString()) +" "+ mount  + " -td " + imagename
        Thread.sleep(2000)
        logger.info("sleeping for 2secondes. TODO !! Fix delay needed for possible dockerized server initialization time... :(")
        //logger.debug(dockerimage)
        logger.info(dockercmd)
        val containername = dockercmd.!!.trim()
        runningContainer = Some(containername)
      }else{
        runNonDocker(env.resolveValueToString(startcmd.inputs("CMD").asString()))
      }
      EventManager.emit(new EventMessage("service-started",this.name,""))
      _isRunning = true
    }
    true
  }

  def testService(): String ={
    if(test.isDefined){
      val env = initEnv
      val absolutecmd = env.resolveValueToString(test.get).replace("\n"," ").replaceAll("^\\./",getDefDir+"/")
      val cmdtolaunch = "python "+ConfManager.get("cpm_home_dir")+"/"+ConfManager.get("shell_exec_bin")+" "+absolutecmd
      Process(cmdtolaunch,new java.io.File(getDefDir)).!!
    }else{
      "no test command defined"
    }
  }

  def stop():Boolean={
    _lock.synchronized{
      val env = initEnv
      if(startcmd.needsDocker()){
        if(runningContainer.isDefined){
          DockerManager.removeService(runningContainer.get)
        }
      }
      if(stopcmd.isDefined){
        runNonDocker(env.resolveValueToString(stopcmd.get.inputs("CMD").asString()))
      }
      _isRunning = false
      EventManager.emit(new EventMessage("service-stopped",this.name,""))
      true
    }
  }

  private def runNonDocker(cmd:String) = {
    val env = initEnv
    val absolutecmd = cmd.replace("\n"," ").replaceAll("^\\./",getDefDir+"/")
    val cmdtolaunch = "python "+ConfManager.get("cpm_home_dir")+"/"+ConfManager.get("shell_exec_bin")+" "+absolutecmd

    val executor = Executors.newSingleThreadExecutor()
    executor.execute(new Runnable {
      override def run(): Unit = {


        Process(cmdtolaunch,new java.io.File(getDefDir)).!

      }
    })
    logger.info("service "+this.name+" started ")
  }

  def isRunning():Boolean={
    _lock.synchronized{
      _isRunning
    }
  }

  def toJson : JSONObject = {
    val env = initEnv
    val json = new JSONObject()
    json.put("name",name)
    json.put("desc",desc)
    json.put("test",test.isDefined)
    if(log.isDefined){
      json.put("log",env.resolveValueToString(log.get))
    }
    json.put("status",_isRunning)
    val outputsjson = new JSONObject()
    outputs.foreach(output=>{
      val jsonparamval = output._2.toJson
      if(jsonparamval.has("value")){
        jsonparamval.put("value",env.resolveValueToString(jsonparamval.get("value").asInstanceOf[String]))
      }
      outputsjson.put(output._1,jsonparamval)
    })
    json.put("outputs",outputsjson)
    json
  }

}


object Service{
  def initCMD(conf:Any,name:String):CMDVal={
    val inputs : java.util.Map[String,Any] = conf match {
      case YamlMap(map) => map
      case _ => throw new Exception("malformed module value")
    }
    CMDVal(name,Some(inputs))
  }
}
