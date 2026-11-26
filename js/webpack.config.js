const MiniCssExtractPlugin = require("mini-css-extract-plugin");
const fs = require('fs');
const path = require('path');

/**
 * Writes each asset's content hash to a `.hash` file so the Scala server
 * can construct URLs like `/static/dist/a1b2c3.main.css` at runtime.
 */
class WriteHashPlugin {
  apply(compiler) {
    compiler.hooks.afterEmit.tap('WriteHashPlugin', (compilation) => {
      const distDir = compiler.outputPath;
      for (const assetName of Object.keys(compilation.assets)) {
        const match = assetName.match(/^([a-f0-9]{10,})\.(.+)\.(css|js)$/);
        if (match) {
          const [, hash, name, ext] = match;
          fs.writeFileSync(path.join(distDir, `${name}.${ext}.hash`), hash);
        }
      }
    });
  }
}

module.exports = {
  output: {
    filename: '[contenthash].[name].js',
  },
  module: {
    rules: [
      {
        test: /\.(sa|sc|c)ss$/i,
        use: [
          MiniCssExtractPlugin.loader,
          "css-loader",
          "postcss-loader",
        ],
      },
    ],
  },
  plugins: [
    new MiniCssExtractPlugin({
      filename: '[contenthash].[name].css',
    }),
    new WriteHashPlugin(),
  ],
};
