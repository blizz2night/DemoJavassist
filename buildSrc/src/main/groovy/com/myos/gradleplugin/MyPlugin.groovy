package com.myos.gradleplugin

import com.android.build.api.transform.Context
import com.android.build.api.transform.DirectoryInput
import com.android.build.api.transform.Format
import com.android.build.api.transform.JarInput
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformException
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformInvocation
import com.android.build.api.transform.TransformOutputProvider
import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.pipeline.TransformManager
import javassist.ClassPool
import javassist.CtClass
import javassist.CtMethod
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Plugin;
import org.gradle.api.Project

class MyPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        System.out.println("自定义插件+++");
        def android = project.extensions.getByType(AppExtension)
        android.registerTransform(new MyTransform(project))

    }
}

class MyTransform extends Transform {
    private Project mProject

    MyTransform(Project project) {
        mProject = project;
    }

    @Override
    String getName() {
        return "logtransform"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    @Override
    Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return false
    }

    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        println '--------------------transform 开始-------------------'

        // Transform的inputs有两种类型，一种是目录，一种是jar包，要分开遍历
        def inputs = transformInvocation.inputs
        def outputProvider = transformInvocation.outputProvider
        inputs.each {
            TransformInput input ->
                // 遍历文件夹
                //文件夹里面包含的是我们手写的类以及R.class、BuildConfig.class以及R$XXX.class等
                input.directoryInputs.each {
                    directoryInput ->
                        // 注入代码
                        MyInjectByJavassit.injectToast(directoryInput.file.absolutePath, mProject)
                        println("directory input dest: $directoryInput.file.path")
                        // 获取输出目录
                        def dest = outputProvider.getContentLocation(directoryInput.name,
                                directoryInput.contentTypes, directoryInput.scopes, Format.DIRECTORY)

                        println("directory output dest: $dest.absolutePath")
                        // 将input的目录复制到output指定目录
                        FileUtils.copyDirectory(directoryInput.file, dest)
                }

                //对类型为jar文件的input进行遍历
                input.jarInputs.each {
                        //jar文件一般是第三方依赖库jar文件
                    jarInput ->
                        // 重命名输出文件（同目录copyFile会冲突）
                        def jarName = jarInput.name
                        println("jar: $jarInput.file.absolutePath")
                        def md5Name = DigestUtils.md5Hex(jarInput.file.absolutePath)
                        if (jarName.endsWith('.jar')) {
                            jarName = jarName.substring(0, jarName.length() - 4)
                        }
                        def dest = outputProvider.getContentLocation(jarName + md5Name, jarInput.contentTypes, jarInput.scopes, Format.JAR)

//                        println("jar output dest: $dest.absolutePath")
                        FileUtils.copyFile(jarInput.file, dest)
                }
        }

        println '---------------------transform 结束-------------------'
    }
}
///**
// * 借助 Javassit 操作 Class 文件
// */
class MyInjectByJavassit {

    private static final ClassPool sClassPool = ClassPool.getDefault()

    /**
     * 插入一段Toast代码
     * @param path
     * @param project
     */
    static void injectToast(String path, Project project) {
        // 加入当前路径
        sClassPool.appendClassPath(path)
        // project.android.bootClasspath 加入android.jar，不然找不到android相关的所有类
        sClassPool.appendClassPath(project.android.bootClasspath[0].toString())
        // 引入android.os.Bundle包，因为onCreate方法参数有Bundle
        sClassPool.importPackage('android.os.Bundle')

        File dir = new File(path)
        if (dir.isDirectory()) {
            // 遍历文件夹
            dir.eachFileRecurse { File file ->
                String filePath = file.absolutePath
                println("filePath: $filePath")
                def packageName = dir.relativePath(file).replace('/', '.')
                def index = packageName.lastIndexOf('.class')
                println "packageName=$packageName"

                if (file.name == 'MainActivity.class') {
                    // 获取Class
                    // 这里的MainActivity就在app模块里
                    CtClass ctClass = sClassPool.getCtClass("com.github.blizz2night.mypluginjavassist.MainActivity")
                    println("ctClass: $ctClass")

                    // 解冻
                    if (ctClass.isFrozen()) {
                        ctClass.defrost()
                    }

                    // 获取Method
                    CtMethod ctMethod = ctClass.getDeclaredMethod('onCreate')
                    println("ctMethod: $ctMethod")

                    String toastStr = """ android.widget.Toast.makeText(this,"我是被插入的Toast代码~!!",android.widget.Toast.LENGTH_SHORT).show();
                                      """

                    // 方法尾插入
                    ctMethod.insertAfter(toastStr)
                    ctClass.writeFile(path)
                    ctClass.detach() //释放
                }
            }
        }
    }

}
