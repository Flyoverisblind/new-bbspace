# 哔哔空间 · NewBBSpace

> 基于 [BBSpace](https://github.com/naaammme/bbspace) 的第三方哔哩哔哩客户端二次创作版  
> 持续维护者：[@Flyoverisblind](https://github.com/Flyoverisblind)  
> 原项目作者：[naaammme](https://github.com/naaammme)

[![Release](https://img.shields.io/github/v/release/Flyoverisblind/new-bbspace?style=flat-square&color=FB7299)](https://github.com/Flyoverisblind/new-bbspace/releases)
[![License](https://img.shields.io/github/license/Flyoverisblind/new-bbspace?style=flat-square&color=00A1D6)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/Flyoverisblind/new-bbspace/build.yml?style=flat-square)](https://github.com/Flyoverisblind/new-bbspace/actions)

## ✨ 项目简介

NewBBSpace 是一个使用 **Kotlin + Jetpack Compose + Media3** 构建的哔哩哔哩第三方客户端。  
在原 BBSpace 的基础上，持续补充播放、收藏夹、UP 空间、录播、动态、共享元素转场与液态玻璃体验。

- 当前仓库：<https://github.com/Flyoverisblind/new-bbspace>
- 下载地址：<https://github.com/Flyoverisblind/new-bbspace/releases>
- Telegram 群组：<https://t.me/newbbspace>

## 🚀 新增与优化

### 播放

- 合集 / 分 P 连播，支持播放全部
- 循环播放、自动下一个、播完暂停
- 播放页合集面板
- 收藏夹视频播放全部
- 播放页画质、音频、倍速、全屏、手势、小窗

### UP 空间与直播回放

- UP 空间 `tab2` 动态投稿分类
- 视频 / 直播回放 / 合集列表分类展示
- 投稿分类横向滚动筛选，分类多也不杂乱
- 直播回放按 `is_live_playback` 过滤
- 录播使用真实 `avid / cid`，播放、点赞、投币、收藏、画质、音频全部走普通视频链路

### 动画与视觉

- 首页视频卡片 → 全屏播放页 ColorOS 风格无缝动画
- 所有视频入口统一动画：首页、搜索、UP 空间、动态、历史、稍后再看、收藏夹
- 返回时文字先出现，封面按对应卡片封面区域慢慢缩小归位
- 侧边封面卡片按左侧封面位置收回，不会突然缩小
- 圆角线性过渡，转场独立浮层
- 转场开关、圆角、动画速度可调
- 底部导航栏与搜索按钮接入 Haze 背板模糊
- 液态玻璃模糊、透明度、高光、颗粒可调

## 🧱 技术栈

- Kotlin
- Jetpack Compose / Material 3
- AndroidX Media3 / ExoPlayer
- Hilt / KSP
- Room
- OkHttp / Retrofit / gRPC
- Haze

## 🔧 构建

系统要求：Android 7.0 及以上  
开发环境：JDK 17、Android SDK 36

```bash
./gradlew assembleRelease
```

## 📦 参考项目

特别感谢以下开源项目：

- [BBSpace](https://github.com/naaammme/bbspace)
- [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus)
- [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect)
- [Haze](https://github.com/chrisbanes/haze)
- [androidx/media](https://github.com/androidx/media)
- [DanmakuFlameMaster](https://github.com/naaammme/DanmakuFlameMaster)

## 📄 License

[GPL-3.0](LICENSE)
