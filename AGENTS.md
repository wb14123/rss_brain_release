# Claude Code Guidelines

When trying to understand the project structure, tech stack, or how to build/run/deploy, read `readme.md` first.

## Database Schema Changes

When adding new columns to existing tables, do NOT include `ALTER TABLE` statements in the DAO code. Only update the `CREATE TABLE` statement (used for fresh installs). After the change, tell the user the SQL migration they need to run manually on existing databases.

## Frontend Style

Do not use inline CSS `style` attributes in webview Scala HTML (Scalatags). Define CSS classes in `js/css/main.css` instead.
