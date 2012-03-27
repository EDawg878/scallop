package org.rogach.scrollop

import scala.reflect.Manifest

object Scrollop {
  def apply(args:Seq[String]):Scrollop = new Scrollop(args,List(),List(),None,None)
  def apply():Scrollop = apply(List())
}

case class Scrollop(args:Seq[String], opts:Seq[OptDef], propts:Seq[PropDef], vers:Option[String], bann:Option[String]) {
  lazy val pargs = parse(args)
  def parse(args:Seq[String]):List[(Option[String],Option[String],List[String])] = {
    args.toList match {
      case a :: rest if a.startsWith("--") =>
        (None,Some(a.drop(2)),rest.takeWhile(!_.startsWith("-"))) :: 
        parse(rest.dropWhile(!_.startsWith("-")))
      case a :: rest if a.startsWith("-") =>
        if (propts.find(_.char == a(1)).isDefined) {
          (Some(a(1).toString), None, (a.drop(2) +: rest.takeWhile(!_.startsWith("-"))).filter(_.size > 0)) ::
          parse(rest.dropWhile(!_.startsWith("-")))
        } else {
          a.drop(1).init.map(i => (Some(i.toString), None, List[String]())).toList :::
          List((Some(a.last.toString),None,rest.takeWhile(!_.startsWith("-")))) :::
          parse(rest.dropWhile(!_.startsWith("-")))
        }
      case Nil => List()
      case a => throw new OptionParseException("Failed to parse options: " + a.mkString(" "))// there should be options!
    }
  }
  def opt[A](name:String, short:Char = 0.toChar, descr:String = "", default:Option[A] = None, required:Boolean = false, arg:String = "arg")(implicit conv:ValueConverter[A], m:Manifest[A]):Scrollop = {
    val eShort = if (short == 0.toChar) None else Some(short)
    val argType =
      if (m <:< implicitly[Manifest[Boolean]]) ArgType.FLAG
      else if (m <:< implicitly[Manifest[List[_]]]) ArgType.LIST
      else ArgType.SINGLE
    this.copy(opts = opts :+ new OptDef(name,eShort,descr,conv,m,default,required,arg, argType))
  }
  def props(name:Char,descr:String = ""):Scrollop = {
    this.copy(propts = propts :+ new PropDef(name,descr))
  }
  def version(v:String) = this.copy(vers = Some(v))
  def banner(b:String) = this.copy(bann = Some(b))
  def help:String = opts.sortBy(_.name).map(o => o.help(getOptShortName(o))).mkString("\n")
  def args(a:Seq[String]) = this.copy(args = args ++ a)
  def get[A](name:String)(implicit m:Manifest[A]):Option[A] = {
    val opt = opts.find(_.name == name).getOrElse(throw new UnknownOption("Unknown option requested: '%s'" format name))
    val sh = getOptShortName(opt)
    if (!(m <:< opt.m)) {
      throw new WrongTypeRequest("")
    }
    opt.conv.parse(pargs.filter(a => a._2.map(opt.name ==)
    .getOrElse(sh.map(a._1.get.head == _).getOrElse(false))).map(_._3)).right.get
    .orElse(opt.default).asInstanceOf[Option[A]]
  }
  def prop(name:Char, key:String):Option[String] = {
    pargs.filter(_._1 == Some(name.toString)).flatMap { p =>
      val rgx = """([^=]+)=(.*)""".r
      p._3.collect {
        case rgx(key, value) => (key, value)
      }
    }.find(_._1 == key).map(_._2)
  }
  def apply[A](name:String)(implicit m:Manifest[A]):A = get(name)(m).get
  private def getOptShortName(o:OptDef):Option[Char] =
    o.short.orElse {
      val sh = o.name.head
      if ((opts.map(_.short).flatten ++ propts.map(_.char)).contains(sh)) None
      else if (opts.takeWhile(o !=).filter(!_.short.isDefined).map(_.name.head).contains(sh)) None
           else Some(sh)
    }

  def verify = {
    if (pargs.find(_._2 == Some("help")).isDefined) {
      vers.foreach(println)
      bann.foreach(println)
      println(help)
      sys.exit(0)
    }
    if (vers.isDefined && pargs.find(_._2 == Some("version")).isDefined) {
      println("version")
      sys.exit(0)
    }
    // long options must not clash
    opts.groupBy(_.name).filter(_._2.size > 1).foreach(a => throw new IdenticalOptionNames("Long option name '%s' is not unique" format a._1))
    // short options must not clash
    (opts.map(_.short).flatten ++ propts.map(_.char)).groupBy(a=>a).filter(_._2.size > 1).foreach(a => throw new IdenticalOptionNames("Short option name '%s' is not unique" format a._1))
    // check that there are no garbage options
    pargs.foreach { arg =>
      if (!arg._2.map(n => opts.find(_.name == n).isDefined)
          .getOrElse((opts.map(o => o.short.getOrElse(o.name.head)) ++ propts.map(_.char)).find(arg._1.get.head ==).isDefined))
        throw new UnknownOption("Unknown option: %s" format arg._1.getOrElse(arg._2.get))
    }
    
    opts.foreach { o => 
      val sh = getOptShortName(o)
      val params = pargs.filter(a => a._2.map(o.name ==).getOrElse(sh.map(a._1.get.head == _).getOrElse(false))).map(_._3)
      val res = o.conv.parse(params)
      if (res.isLeft) throw new WrongOptionFormat("Wrong format for option '%s': %s" format (o.name, params.map(_.mkString).mkString))
      if (o.required && !res.right.get.isDefined && !o.default.isDefined) throw new RequiredOptionNotFound("Required option '%s' not found" format o.name)
    }
    this
  }
}

case class OptDef(name:String, short:Option[Char], descr:String, conv:ValueConverter[_],m:Manifest[_], default:Option[Any], required:Boolean, arg:String, argType:ArgType.V) {
  def help(sh:Option[Char]):String = {
    val line = 
      List[Option[String]](sh.map("-" +),Some("--" + name)).flatten.mkString(", ") + "  " + argType.fn(arg)
    var text = List[String]("")
    descr.split(" ").foreach { w =>
      if (text.last.size + 1 + w.size <= 76) {
        text = text.init :+ (text.last + w + " ")
      } else if (text.last.size + w.size <= 76) {
        text = text.init :+ (text.last + w)
      } else text :+= w
    }
    (line + "\n" + text.map("    " +).mkString("\n")).trim
  }
}
case class PropDef(char:Char, descr:String)
object ArgType extends Enumeration {
  case class V(fn: String => String) extends Val
  val FLAG = V(_ => "")
  val SINGLE = V("<"+_+">")
  val LIST = V("<"+_+">...")
}

