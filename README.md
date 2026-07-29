# pi4j-core

Pure Java multi-LLM AI Agent framework core library.

## Overview

pi4j-core is the core runtime for building AI agents that interact with multiple LLM providers. It provides:

- **Agent Loop**: Double-loop engine (follow-up + tool execution)
- **Multi-Provider Support**: OpenAI (Completions/Responses), Anthropic (Messages), Google (Gemini)
- **Streaming**: SSE-based streaming with typed event system
- **Extensible Tool System**: SPI-based tool registry with risk classification
- **Policy Chain**: Three-state tool execution policy (ALLOW/CONFIRM/DENY)
- **Context Management**: Pluggable context contributors
- **Session & Workspace**: SPIs for session lifecycle and filesystem boundary control

## Dependencies

- JDK 17+
- OkHttp 4
- Gson
- Hutool Core

## Usage

```xml
<dependency>
    <groupId>com.github.hbq969</groupId>
    <artifactId>pi4j-core</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

## License

MIT
