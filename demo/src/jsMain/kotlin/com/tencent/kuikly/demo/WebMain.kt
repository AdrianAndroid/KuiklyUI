/*
 * demo 业务 bundle（nativevue2.js）的 Web/MiniApp 平台入口。
 *
 * Kotlin/JS 对 executables 的 main() 由 webpack 在 bundle 加载时自动执行（与
 * h5App.js 的 main() 同理）。这里在宿主创建任何页面之前完成 SFTP 专题页面的
 * PagerManager 注册，从根上消除 PagerNotFoundException / 白屏。
 */
package com.tencent.kuikly.demo

import com.tencent.kuikly.demo.pages.sftp.registerSftpWebPages

fun main() {
    registerSftpWebPages()
}