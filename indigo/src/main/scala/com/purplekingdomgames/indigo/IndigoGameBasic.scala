package com.purplekingdomgames.indigo

import com.purplekingdomgames.indigo.gameengine.Startup

/**
  * A trait representing a minimal set of functions to get your game running
  * @tparam StartupData The class type representing your successful startup data
  * @tparam Model The class type representing your games model
  */
trait IndigoGameBasic[StartupData, Model] {

  val config: GameConfig

  val assets: Set[AssetType]

  def setup(assetCollection: AssetCollection): Either[StartupErrors, StartupData]

  def initialModel(startupData: StartupData): Model

  def update(gameTime: GameTime, model: Model): GameEvent => Model

  def render(gameTime: GameTime, model: Model, frameInputEvents: FrameInputEvents): SceneGraphUpdate

  def main(args: Array[String]): Unit =
    Indigo.game
      .withConfig(config)
      .withAssets(assets)
      .startUpGameWith(ac => Startup.fromEither(setup(ac)))
      .usingInitialModel(initialModel)
      .updateModelUsing(update)
      .drawUsing(render)
      .start()

}
