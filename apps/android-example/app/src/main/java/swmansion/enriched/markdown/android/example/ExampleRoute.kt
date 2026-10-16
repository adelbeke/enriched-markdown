package swmansion.enriched.markdown.android.example

enum class ExampleRoute {
  Home,
  Playground,
  Text,
  MaterialTheme,
  Halloween,
  Input,
  Stream,
  Storybook,
  ;

  /** Screens that draw their own full-bleed night get a transparent bar instead of the mint one. */
  val paintsOwnBackdrop: Boolean get() = this == Halloween
}
