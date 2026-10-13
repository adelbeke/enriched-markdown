package com.swmansion.enriched.markdown.plugin

import android.content.Context
import com.swmansion.enriched.markdown.parser.MarkdownASTNode
import com.swmansion.enriched.markdown.parser.Md4cFlags
import com.swmansion.enriched.markdown.renderer.NodeRenderer
import com.swmansion.enriched.markdown.renderer.RendererConfig

/** Apps enable plugins freely; only implementing one needs opt-in. */
interface MarkdownPlugin {
  /** Stable across releases: it identifies the plugin's registrations and its rendered segments. */
  val id: String

  @InternalPluginApi
  fun install(registry: PluginRegistry)

  /**
   * The flags to parse with wherever this plugin is enabled, given those the view was set up with;
   * a plugin turns on the syntax extensions that produce the nodes it renders, so enabling it is
   * enough. Plugins adjust in the order they are installed, each seeing the previous one's result.
   */
  @InternalPluginApi
  fun adjustParserFlags(flags: Md4cFlags): Md4cFlags = flags
}

@InternalPluginApi
interface PluginRegistry {
  /**
   * Claim a node type outright; the plugin renders it into the spannable instead of core.
   *
   * The factory runs once per [com.swmansion.enriched.markdown.renderer.RendererFactory], not per
   * node, so a renderer may cache whatever its config and context allow.
   */
  fun registerNodeRenderer(
    type: MarkdownASTNode.NodeType,
    factory: (RendererConfig, Context) -> NodeRenderer,
  )

  /**
   * Claim a node type as its own block segment with its own View. Only top-level nodes become
   * segments; nested ones still go through the type's node renderer.
   */
  fun registerBlockSegment(
    type: MarkdownASTNode.NodeType,
    segment: PluginBlockSegment<*>,
  )
}
