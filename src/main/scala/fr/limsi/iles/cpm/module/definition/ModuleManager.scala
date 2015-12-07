package fr.limsi.iles.cpm.module.definition

import java.io.{File, FileInputStream}

import com.typesafe.scalalogging.LazyLogging
import fr.limsi.iles.cpm.utils._
import org.json.{JSONArray, JSONObject}
import org.yaml.snakeyaml.Yaml

import scala.io.Source


class ModTree
case class ModLeaf(modName:String,modDefFilePath:String) extends ModTree
case class ModNode(modPath:String,modItems:List[ModTree]) extends ModTree

/**
 * Created by buiquang on 9/7/15.
 */
object ModuleManager extends LazyLogging{

  var modules = Map[String,ModuleDef]()
  private var modulestree :ModNode = ModNode("/",List[ModTree]())

  /**
   * Check every modules found in the listed directory supposely containing modules definition/implementation/resources
   * Check for consistency
   */
  def init()={
    // list all modules and init independant fields
    if(!ConfManager.confMap.containsKey("modules_dir")){
      throw new Exception("no module directories set in configuration!")
    }

    val list : java.util.ArrayList[String] = ConfManager.get("modules_dir").asInstanceOf[java.util.ArrayList[String]]
    val iterator = list.iterator()
    while(iterator.hasNext){
      var modlist = List[ModTree]()
      val path = iterator.next()
      val file = new File(path)
      if(file.exists()){
        findModuleConf(file,ModuleManager.initModule) match{
          case Some(x:ModTree)=>{
            modlist = x :: modlist
          }
          case None => {

          }
        }
        modulestree = ModNode("/",ModNode(file.getParent,modlist)::modulestree.modItems)
      }
    }

    var firstRun = true
    var discarded = ""
    while(discarded != "" || firstRun){
      if(discarded!=""){
        modules -= discarded
      }
      discarded = ""
      firstRun = false
      var curmod = ""
      try{
        modules.values.foreach(m => {
          curmod = m.name
          val yaml = new Yaml()
          val wd = (new File(m.confFilePath)).getParent
          val ios = new FileInputStream(m.confFilePath)
          val confMap = yaml.load(ios).asInstanceOf[java.util.Map[String,Any]]
            m.exec = ModuleDef.initRun(confMap,wd,m.inputs)
        })
      }catch{
        case e:Throwable => discarded = curmod; e.printStackTrace(); logger.error("error when initiation exec configuration for module "+curmod+". This module will therefore be discarded");
      }

    }

    logger.info("Finished initializing modules")
  }

  /**
   * Reload module definition
   */
  def reload()={
    modules = Map[String,ModuleDef]()
    modulestree = ModNode("/",List[ModTree]())
    init()
  }

  def jsonTreeExport(tree:ModTree):Object = {
    tree match {
      case ModNode(dirpath,list) => {
        var json = new JSONObject()
        var array = new JSONArray()
        list.foreach(subtree => {
          array.put(jsonTreeExport(subtree))
        })
        json.put("folder",true)
        json.put("foldername",dirpath)
        json.put("items",array)
        json
      }
      case ModLeaf(name,filepath) => {
        if(modules.contains(name)){
          var json = new JSONObject()
          json.put("modulename",name)
          json.put("sourcepath",filepath)
          json.put("module",new JSONObject(modules(name).serialize()(true)))
          json.put("source",Source.fromFile(modules(name).confFilePath).getLines.foldLeft("")((agg,line)=>agg+"\n"+line))
        }else{
          var json = new JSONObject()
          json.put("modulename",name)
          json.put("sourcepath",filepath)
          json.put("source",Source.fromFile(filepath).getLines.foldLeft("")((agg,line)=>agg+"\n"+line))
        }
      }
    }
  }

  /**
   * Export module defs to json
   * @param onlyname
   * @return
   */
  def jsonExport(onlyname:Boolean):String ={
    /*var json ="["
    modules.foreach(el=>{
      if(onlyname){
        json += el._1 +","
      }else{
        json += el._2.serialize()(true)+","
      }
    })
    json.substring(0,json.length-1)+"]"*/
    jsonTreeExport(modulestree).toString
  }

  /**
   * Returns printable string of module defs
   * @param onlyname
   * @return
   */
  def ls(onlyname:Boolean) : String= {
    modules.foldRight("")((el,agg) => {
      {if(onlyname){
         el._2.name
      }else{
        el._2.toString
      }} + "\n" + agg
    })
  }

  /**
   * Apply a function on file that match module extension : .module
   * @param curFile
   * @param f
   */
  private def findModuleConf(curFile:java.io.File,f:java.io.File => Option[String]) :Option[ModTree]={
    if(curFile.isFile){
      if(curFile.getName().endsWith(".module")){
        f(curFile) match {
          case Some(modulename)=>{
            Some(ModLeaf(modulename,curFile.getCanonicalPath))
          }
          case None =>{None}
        }
      }else{
        None
      }
    }else if(curFile.isDirectory){
      var list = List[ModTree]()
      val iterator = curFile.listFiles().iterator
      while(iterator.hasNext){
        val file = iterator.next()
        findModuleConf(file,f) match{
          case Some(x:ModTree)=>{
            list = x :: list
          }
          case None => {

          }
        }
      }
      if(list.length > 0)
        Some(ModNode(curFile.getName,list))
      else
        None
    }else{
      logger.warn("File at path "+curFile.getPath+" is neither file nor directory!")
      None
    }
  }

  /**
   * Init module definition conf from definition file
   * @param moduleConfFile
   */
  private def initModule(moduleConfFile:File):Option[String]={
    var foundModule : Option[String] = None
    try{
      val modulename = moduleConfFile.getName.substring(0,moduleConfFile.getName.lastIndexOf('.'))
      logger.debug("Initiating module "+modulename)

      val yaml = new Yaml()
      val ios = new FileInputStream(moduleConfFile)
      val confMap = yaml.load(ios).asInstanceOf[java.util.Map[String,Any]]
      val module = new ModuleDef(moduleConfFile.getCanonicalPath,
        ModuleDef.initName(modulename,confMap),
        ModuleDef.initDesc(confMap),
        ModuleDef.initInputs(confMap),
        ModuleDef.initOutputs(confMap),
        ModuleDef.initLogs(confMap),
        Nil
      );
      // check if module name already exist
      modules.get(modulename) match {
        case Some(m:ModuleDef) => throw new Exception("Module already exist, defined in "+moduleConfFile.getParent)
        case None => foundModule = Some(modulename); modules += (modulename -> module); foundModule
      }
    }catch{
      case e: Throwable => e.printStackTrace(); logger.error("Wrong module defintion in "+moduleConfFile.getCanonicalPath+"\n"+e.getMessage+"\n This module will not be registered."); foundModule
    }

  }



}
