@echo off
echo Configuring your repository...
git remote remove origin
git remote add origin https://github.com/angahjee1994/repo.git

echo.
echo Pushing code to GitHub...
echo (You may be asked to sign in via a browser or enter a token)
git push -u origin master
git push -u origin builds

echo.
echo Done! If you see errors above, please share them.
pause
