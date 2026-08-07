# EternalScript 2.0 API Migration

This migration is immediate. EternalScript does not ship a deprecated adapter
for the former public `Script` base class or constructor-time registration.

## English

### 1. Extend `EternalScript`

```kotlin
import eternalScript.api.script.EternalScript

class Example : EternalScript() {
    override fun onEnable() {
        // activation setup
    }
}
```

Replace direct `Script` inheritance with `EternalScript`. Public project code
must import from `eternalScript.api`; `eternalScript.core` is an implementation
boundary and is not supported script API.

### 2. Move API calls out of constructors

The no-argument constructor runs before EternalScript attaches the managed
runtime bridge. Calls to `plugin`, `events`, `commands`, `track`, `task`,
`launch`, or `async` from property initializers, `init` blocks, or the
constructor now fail with a lifecycle error. Move event and command definitions
to `onEnable`. Use `onDisable` for idempotent external cleanup.

```kotlin
class JoinFeature : EternalScript() {
    override fun onEnable() {
        events {
            on<org.bukkit.event.player.PlayerJoinEvent> { event ->
                event.player.sendMessage("Welcome")
            }
        }
    }
}
```

Each activation creates fresh event and command definitions. After final
disposal, API calls are rejected instead of reaching an old generation.

### 3. Replace the flat registration DSL

Flat `event<T> {}` and `command("name") {}` calls were removed. Group each kind
of definition under its lifecycle-owned scope:

```kotlin
override fun onEnable() {
    events {
        on<org.bukkit.event.player.PlayerJoinEvent> { event ->
            event.player.sendMessage("Welcome")
        }
    }

    commands {
        command("greet") {
            aliases("hello")
            permission("example.greet")
            suggests {
                listOf("world")
            }
            executes {
                sender.sendMessage("$label ${arguments.joinToString()}")
            }
        }
    }
}
```

The former `tabCompleter { sender, alias, args -> }` callback is now
`suggests {}` with `sender`, `alias`, and `arguments` context properties. The
former `executor { sender, label, args -> }` callback is now `executes {}` with
`sender`, `label`, and `arguments`. The builder is supplied only as the nested
DSL receiver; scripts do not construct or import it. There is no deprecated
adapter for the flat names.

### 4. Verify the complete project

Run `gradlew.bat check`, then `/es check` on the live server, and finally
`/es reload`. The Gradle check verifies the public compile boundary; `/es check`
adds the current server plugin-classloader validation.

## 한국어

### 1. `EternalScript` 직접 상속

기존 공개 `Script` 상속을 `eternalScript.api.script.EternalScript` 직접 상속으로
바꿉니다. 사용자 프로젝트는 `eternalScript.api`만 import해야 하며,
`eternalScript.core`는 지원되는 스크립트 API가 아닙니다.

### 2. 생성자 API 호출을 `onEnable`로 이동

인자 없는 생성자가 끝난 직후, `onEnable` 호출 직전에 런타임 bridge가
연결됩니다. 따라서 property initializer, `init` 블록, 생성자에서 `plugin`,
`events`, `commands`, `track`, `task`, `launch`, `async`를 호출하면 명확한
수명주기 오류가 발생합니다. 이벤트와 명령 정의는 `onEnable`로 옮기고,
외부 자원 정리는 재실행 가능하도록 `onDisable`에서 처리합니다.

각 활성화는 이벤트와 명령 정의를 새로 만듭니다. 최종 dispose 뒤 API 호출도
이전 generation에 접근하지 않고 거부됩니다.

### 3. 평면 등록 DSL 교체

기존의 평면 `event<T> {}`와 `command("name") {}` 호출은 제거되었습니다.
이벤트는 `events {}`에, 명령은 `commands {}`에 묶습니다.

```kotlin
override fun onEnable() {
    events {
        on<org.bukkit.event.player.PlayerJoinEvent> { event ->
            event.player.sendMessage("환영합니다")
        }
    }

    commands {
        command("greet") {
            aliases("hello")
            permission("example.greet")
            suggests {
                listOf("world")
            }
            executes {
                sender.sendMessage("$label ${arguments.joinToString()}")
            }
        }
    }
}
```

기존 `tabCompleter { sender, alias, args -> }`는 `suggests {}`로 바뀌며
`sender`, `alias`, `arguments`를 context property로 사용합니다. 기존
`executor { sender, label, args -> }`는 `executes {}`로 바뀌며 `sender`,
`label`, `arguments`를 사용합니다. builder는 중첩 DSL receiver로만 제공되므로
직접 생성하거나 import하지 않습니다. 이전 평면 이름을 위한 deprecated
호환층은 없습니다.

### 4. 전체 프로젝트 검증

`gradlew.bat check`, 실서버 `/es check`, `/es reload` 순서로 확인합니다.
Gradle은 공개 API 컴파일 경계를 검사하고, `/es check`는 현재 서버의 플러그인
classloader 검증을 추가합니다.
