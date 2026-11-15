
# TeamSheet — V1 Project Requirements

## 1. Project Overview

Build a modern, professional web application called **TeamSheet**, inspired by KEAMK.

TeamSheet allows users to enter a list of players, assign player attributes, choose a game format, and generate balanced teams based on different criteria.

The application should prioritise simplicity, usability and accurate team distribution.

Build V1 as a fully functional MVP with a clean architecture that supports future enhancements.

## 2. Technology Stack

### Frontend
- React
- TypeScript
- Vite
- Tailwind CSS

### Backend
- Kotlin
- Spring Boot
- Spring Web MVC
- Gradle Kotlin DSL
- Java 21

### Database
- No database for V1.
- Use in-memory processing.
- Keep the architecture ready for future database integration.

### Development Environment
- IntelliJ IDEA for backend development.
- React frontend developed in the frontend directory.

## 3. Project Structure

Use a monorepo structure:

```text
teamsheet/
├── frontend/
│   └── src/
│       ├── components/
│       │   ├── PlayerForm.tsx
│       │   ├── PlayerList.tsx
│       │   ├── TeamSettings.tsx
│       │   ├── GameFormatSelector.tsx
│       │   ├── PositionTargets.tsx
│       │   ├── TeamCard.tsx
│       │   └── TeamResults.tsx
│       ├── services/
│       │   └── teamService.ts
│       ├── types/
│       │   └── index.ts
│       ├── App.tsx
│       └── main.tsx
│
└── backend/
    └── src/main/kotlin/com/teamsheet/
        ├── TeamSheetApplication.kt
        ├── controller/
        │   └── TeamController.kt
        ├── service/
        │   ├── TeamService.kt
        │   └── balancing/
        │       ├── TeamBalancer.kt
        │       ├── SkillBalancer.kt
        │       └── PositionBalancer.kt
        └── model/
            └── TeamModels.kt
```

## 4. Player Management

Each player must have:

- Unique ID.
- Name.
- Skill rating (1–5).
- Gender (optional).
- Playing positions (multiple selections).
- Ordered position preferences.

### Skill Ratings

| Rating | Description |
|---|---|
| 1 | Beginner |
| 2 | Below average |
| 3 | Average |
| 4 | Good |
| 5 | Excellent |

### Playing Positions

| Position | Code |
|---|---|
| Goalkeeper | GK |
| Defender | DEF |
| Midfielder | MID |
| Attacker | ATT |

A player can select multiple positions.

For example:

```json
{
  "id": "player-1",
  "name": "John",
  "skillRating": 5,
  "gender": "MALE",
  "positions": ["GK", "DEF", "MID"]
}
```

The order represents player preference:

1. GK — Primary position.
2. DEF — Secondary position.
3. MID — Third preference.

The algorithm should prefer primary positions but allow secondary positions to fill gaps.

## 5. Game Format Selection

Users must be able to select a game format.

Supported presets:

- 5-a-side
- 6-a-side
- 7-a-side
- 11-a-side
- Custom format

### Suggested Default Position Distributions

| Format | GK | DEF | MID | ATT | Total |
|---|---:|---:|---:|---:|---:|
| 5-a-side | 1 | 1 | 2 | 1 | 5 |
| 6-a-side | 1 | 2 | 1 | 2 | 6 |
| 7-a-side | 1 | 2 | 2 | 2 | 7 |
| 11-a-side | 1 | 4 | 3 | 3 | 11 |

These are suggestions, not mandatory formations.

Users must be able to edit the position distribution.

### Custom Format

Allow users to specify:

- Number of players per team.
- Number of goalkeepers.
- Number of defenders.
- Number of midfielders.
- Number of attackers.

Validate that the position targets add up to the selected team size.

## 6. Team Generation Modes

This is a core V1 requirement.

Provide three generation modes.

### Mode 1: Random

- Randomly shuffle players.
- Distribute players evenly across teams.
- Ignore skill and position preferences when assigning teams.

### Mode 2: Balanced by Skill

- Consider each player's skill rating.
- Distribute stronger and weaker players across teams.
- Minimise differences in total and average team skill.
- Keep team sizes as equal as possible.

### Mode 3: Balanced by Skill + Position

This is TeamSheet's main intelligent generation mode.

Requirements:

- Balance average and total skill across teams.
- Distribute players across their preferred positions.
- Prioritise primary positions where possible.
- Use secondary positions when necessary.
- Avoid situations where one team receives most of the defenders, midfielders, attackers or goalkeepers.
- Compare each team's actual composition against the selected position targets.
- Minimise positional imbalances across teams.
- Keep team sizes as equal as possible.

Position balance is a preference, not a strict requirement.

If the available players cannot satisfy the ideal distribution, generate the best possible teams instead of failing.

## 7. Team Balancing Algorithm

Implement the balancing logic inside the Kotlin backend.

Use a multi-criteria optimisation approach.

Suggested initial weighting for Skill + Position mode:

- Skill balance: 50%.
- Position balance: 50%.

The algorithm should:

1. Identify players with fewer available positions.
2. Prioritise players who are harder to place.
3. Consider each player's position preferences.
4. Evaluate the skill distribution across teams.
5. Evaluate the position distribution across teams.
6. Assign flexible players to teams with greater positional needs.
7. Perform improvement swaps between teams where beneficial.
8. Produce the best overall balance possible.

Important considerations:

- A player with multiple positions must only occupy one position in a team.
- For example, a player who can play GK, DEF and MID must not count as three separate players.
- Each generated player assignment should include an effective assigned position.
- The algorithm should support deterministic execution with a fixed random seed for automated testing.
- Handle situations where no player is available for a particular position.
- Avoid unnecessary preference violations when a better assignment is possible.

## 8. Backend Models

Create suitable Kotlin data classes and enums.

Suggested models:

```kotlin
enum class Position {
    GK, DEF, MID, ATT
}

enum class BalancingMode {
    RANDOM,
    SKILL,
    SKILL_AND_POSITION
}

data class Player(
    val id: String,
    val name: String,
    val skillRating: Int,
    val gender: String? = null,
    val positions: List<Position>
)

data class GameFormat(
    val name: String,
    val playersPerTeam: Int,
    val positionTargets: Map<Position, Int>
)

data class TeamRequest(
    val players: List<Player>,
    val numberOfTeams: Int,
    val balancingMode: BalancingMode,
    val gameFormat: GameFormat
)

data class TeamPlayer(
    val player: Player,
    val assignedPosition: Position
)

data class Team(
    val name: String,
    val players: List<TeamPlayer>,
    val totalSkill: Int,
    val averageSkill: Double
)

data class TeamResponse(
    val teams: List<Team>
)
```

Adapt these models where necessary to support proper validation and API design.

## 9. Backend API

Implement:

`POST /api/teams/generate`

The endpoint should accept:

- Players and their attributes.
- Number of teams.
- Balancing mode.
- Game format.
- Position targets.

Return:

- Team names.
- Assigned players.
- Assigned positions.
- Total team skill.
- Average team skill.
- Team sizes.

### Validation

- Player names cannot be empty.
- Skill ratings must be between 1 and 5.
- Number of teams must be valid.
- There must be enough players to populate every team.
- Position targets must be valid.
- Position targets must match the team size.
- Duplicate or invalid player IDs should be handled appropriately.
- Validate that each player has at least one valid position when position balancing is requested.

Return meaningful API error responses.

## 10. Frontend Interface

Design a modern, professional, sports-oriented interface.

### Branding

Application name: TeamSheet.

Suggested colour palette:

- Dark green.
- White.
- Navy.
- Subtle grey backgrounds.

The design should be responsive and work on desktop and mobile.

### Main Interface Sections

#### A. Player Management

- Add individual players.
- Enter multiple players quickly.
- Edit player details.
- Remove players.
- Assign skill ratings.
- Select multiple playing positions.
- Set position preferences.

#### B. Game Settings

- Select game format.
- Select number of teams.
- Configure custom team size.
- Edit position targets.

#### C. Generation Settings

Provide three clear options:

1. Random.
2. Balanced by Skill.
3. Balanced by Skill + Position.

Use descriptive text to explain what each option does.

#### D. Generated Teams

Display each team in an attractive card containing:

- Team name.
- Player names.
- Assigned positions.
- Player skill ratings.
- Total team skill.
- Average team skill.
- Team size.

For Skill + Position mode, display a simple position summary so users can see how balanced the teams are.

Include a regenerate button.

## 11. V1 Scope

All of the following must be implemented in V1:

- Player management.
- Skill ratings.
- Multiple playing positions.
- Primary and secondary position preferences.
- Random team generation.
- Skill-based balancing.
- Skill + Position balancing.
- 5-a-side presets.
- 6-a-side presets.
- 7-a-side presets.
- 11-a-side presets.
- Custom game formats.
- Editable position targets.
- Team size balancing.
- Team results interface.

## 12. Future Features (Not V1)

Do not implement these yet, but keep the architecture extensible:

- Gender-based balancing.
- Saving player lists.
- Saving generated teams.
- Sharing teams through public links.
- PDF export.
- Excel export.
- Visual football pitch and formations.
- Drag-and-drop player assignment.
- User accounts.
- Database persistence.

## 13. Development Instructions

Build incrementally.

Recommended order:

1. Set up the Spring Boot Kotlin backend.
2. Implement player, team and game format models.
3. Implement random generation.
4. Implement skill-based generation.
5. Implement Skill + Position generation.
6. Write automated tests for the balancing algorithms.
7. Build the React frontend.
8. Connect the frontend to the backend.
9. Implement the player management interface.
10. Implement game format and position target settings.
11. Implement the three generation modes.
12. Display team results and balance summaries.
13. Test the complete application.

Keep business logic out of controllers.

Do not over-engineer the initial implementation.

Prioritise algorithm correctness, clean code and a working user experience.

## 14. Definition of Done

V1 is complete when a user can:

1. Open TeamSheet in a browser.
2. Add a list of players.
3. Assign each player a skill rating.
4. Assign multiple positions to each player in order of preference.
5. Select a game format.
6. Adjust preferred position distribution.
7. Choose Random, Balanced by Skill or Balanced by Skill + Position.
8. Generate teams.
9. See assigned players and positions.
10. Compare skill and position distribution across generated teams.
11. Regenerate teams.

**Final instruction:** Build the complete V1 described above. Do not postpone skill or position balancing. Both are essential features of the initial product.
